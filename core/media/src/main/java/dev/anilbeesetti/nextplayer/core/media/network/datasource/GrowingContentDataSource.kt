package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * [DataSource] for a `content://` URI that may still be growing (incomplete download) when the
 * URI cannot be resolved to a filesystem path for [GrowingFileDataSource].
 *
 * Opens via [ContentResolver.openAssetFileDescriptor] / PFD and always returns
 * [C.LENGTH_UNSET]. On EOF, reopens the descriptor and seeks back to [readPosition] if more
 * bytes became available; polls ~50ms and only signals end-of-input after a longer stable idle
 * (~30s), matching [GrowingFileDataSource] VLC-style wait through downloader buffer pauses.
 * Recent last-modified (when queryable) or recent length growth also blocks EOF.
 *
 * When the AFD reports a large declared length but [SparseAwareFileLength] finds a smaller
 * SEEK_HOLE or zero-tail tip (ADM sparse / 1DM zero preallocation), reads are capped at the tip
 * and we poll until it advances — same semantics as [GrowingFileDataSource]. Download-manager
 * path / display-name heuristics force zero-tail scanning when SEEK_HOLE is useless.
 *
 * Never uses Media3's fixed-length [androidx.media3.datasource.ContentDataSource] for this path.
 */
@UnstableApi
class GrowingContentDataSource(
    context: Context,
) : BaseDataSource(/* isNetwork = */ false) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    private var uri: Uri? = null
    private var assetFileDescriptor: AssetFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var readPosition: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    @Volatile
    private var closed = false

    /** Last observed *readable* tip (SEEK_HOLE / zero-tail aware when possible). */
    private var lastObservedLength: Long = -1L
    /** Last AFD / channel declared length (may include sparse holes / zero padding). */
    private var lastDeclaredLength: Long = -1L
    private var lastGrowthElapsedMs: Long = 0L
    private var displayName: String? = null
    private var preferZeroTailScan: Boolean = false
    private var cachedZeroTailTip: Long = -1L
    private var lastTipProbeElapsedMs: Long = 0L

    private val lock = Any()

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        val openUri = dataSpec.uri
        if (!ContentResolver.SCHEME_CONTENT.equals(openUri.scheme, ignoreCase = true)) {
            throw IOException("GrowingContentDataSource requires content:// URI: $openUri")
        }

        uri = openUri
        displayName = queryDisplayName(openUri)
        preferZeroTailScan = DownloadPathHeuristic.looksIncompleteDownload(
            openUri.toString(),
            displayName,
        ) || DownloadPathHeuristic.looksLikeDownloadManagerPath(openUri.lastPathSegment)
        cachedZeroTailTip = -1L
        transferInitializing(dataSpec)

        try {
            waitUntilReadable(openUri, dataSpec.position)
            openDescriptorAt(openUri, dataSpec.position)
            readPosition = dataSpec.position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                C.LENGTH_UNSET.toLong()
            }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Failed to open growing content URI $openUri", e)
        }

        opened = true
        transferStarted(dataSpec)
        // Always unset so the player keeps reading while the download grows.
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val openUri = uri ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }

        while (true) {
            throwIfClosedOrInterrupted()

            val stream = inputStream
            if (stream == null) {
                openDescriptorAt(openUri, readPosition)
                continue
            }

            // Cap reads at sparse-aware tip so we never consume hole zeros as media.
            refreshReadableTip()
            val readable = lastObservedLength
            if (readable >= 0L && readable != AssetFileDescriptor.UNKNOWN_LENGTH) {
                val available = readable - readPosition
                if (available <= 0L) {
                    if (SparseAwareFileLength.isSparsePartial(lastDeclaredLength, readable)) {
                        // Tip reached but sparse tail remains — poll like growing.
                        if (isDownloadFinished(openUri)) {
                            return C.RESULT_END_OF_INPUT
                        }
                        sleepInterruptibly(POLL_INTERVAL_MS)
                        reopenAt(openUri, readPosition)
                        continue
                    }
                    // Fall through to stream EOF / reopen logic below.
                } else {
                    val capped = minOf(toRead.toLong(), available).toInt()
                    val bytesRead = try {
                        stream.read(buffer, offset, capped)
                    } catch (_: IOException) {
                        reopenAt(openUri, readPosition)
                        continue
                    }
                    if (bytesRead > 0) {
                        readPosition += bytesRead
                        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                            bytesRemaining -= bytesRead
                        }
                        noteObservedLength(readPosition)
                        bytesTransferred(bytesRead)
                        return bytesRead
                    }
                    // Unexpected EOF inside tip — reopen.
                    reopenAt(openUri, readPosition)
                    continue
                }
            }

            val bytesRead = try {
                stream.read(buffer, offset, toRead)
            } catch (_: IOException) {
                reopenAt(openUri, readPosition)
                continue
            }

            if (bytesRead > 0) {
                readPosition += bytesRead
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= bytesRead
                }
                noteObservedLength(readPosition)
                bytesTransferred(bytesRead)
                return bytesRead
            }

            // EOF on current stream — reopen; if length grew past readPosition, continue.
            val previousLength = lastObservedLength
            reopenAt(openUri, readPosition)
            val newLength = lastObservedLength
            if (newLength > previousLength && newLength > readPosition) {
                continue
            }
            if (newLength > readPosition) {
                // Same reported length but stream ended early; try reading again after reopen.
                continue
            }
            if (SparseAwareFileLength.isSparsePartial(lastDeclaredLength, newLength)) {
                sleepInterruptibly(POLL_INTERVAL_MS)
                continue
            }

            if (isDownloadFinished(openUri)) {
                return C.RESULT_END_OF_INPUT
            }
            sleepInterruptibly(POLL_INTERVAL_MS)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        synchronized(lock) {
            uri = null
            displayName = null
            try {
                closeDescriptorQuietly()
            } finally {
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }

    private fun waitUntilReadable(uri: Uri, position: Long) {
        var absentSince = 0L
        var stableLength = -1L
        var stableSince = 0L
        var waitStarted = 0L
        while (true) {
            throwIfClosedOrInterrupted()
            try {
                openDescriptorAt(uri, 0L)
                val length = lastObservedLength
                val declared = lastDeclaredLength
                if (position <= 0L) {
                    // Tiny / empty content still succeeds with LENGTH_UNSET at open.
                    return
                }
                if (length == AssetFileDescriptor.UNKNOWN_LENGTH || length >= position) {
                    // Seek target reachable (or unknown growing length); open() will position.
                    openDescriptorAt(uri, position)
                    return
                }
                val now = System.currentTimeMillis()
                if (waitStarted == 0L) {
                    waitStarted = now
                }
                val gap = position - length
                val looksGrowing = looksPartialName(displayName) ||
                    looksPartialName(uri.lastPathSegment) ||
                    preferZeroTailScan ||
                    DownloadPathHeuristic.looksLikeDownloadManagerPath(uri.toString()) ||
                    (lastGrowthElapsedMs > 0L && now - lastGrowthElapsedMs < RECENT_GROWTH_WINDOW_MS) ||
                    SparseAwareFileLength.isSparsePartial(declared, length)
                // Cue-style far seeks on a growing content URI: fail fast after a short wait.
                if (gap > CUE_SEEK_GAP_BYTES &&
                    looksGrowing &&
                    now - waitStarted >= CUE_SEEK_FAIL_FAST_MS
                ) {
                    throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                }
                if (SparseAwareFileLength.isSparsePartial(declared, length) ||
                    looksPartialName(displayName)
                ) {
                    stableLength = length
                    stableSince = now
                    sleepInterruptibly(POLL_INTERVAL_MS)
                    continue
                }
                if (length == stableLength) {
                    if (stableSince == 0L) {
                        stableSince = now
                    } else if (
                        now - stableSince >= STABLE_DURATION_MS &&
                        !looksPartialName(displayName)
                    ) {
                        throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                    }
                } else {
                    stableLength = length
                    stableSince = now
                }
            } catch (e: DataSourceException) {
                throw e
            } catch (_: FileNotFoundException) {
                if (absentSince == 0L) {
                    absentSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - absentSince >= STABLE_DURATION_MS) {
                    throw FileNotFoundException(uri.toString())
                }
            } catch (_: IOException) {
                if (absentSince == 0L) {
                    absentSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - absentSince >= STABLE_DURATION_MS) {
                    throw FileNotFoundException(uri.toString())
                }
            }
            sleepInterruptibly(POLL_INTERVAL_MS)
        }
    }

    private fun isDownloadFinished(uri: Uri): Boolean {
        if (looksPartialName(displayName) ||
            looksPartialName(uri.lastPathSegment) ||
            preferZeroTailScan ||
            DownloadPathHeuristic.looksLikeDownloadManagerPath(uri.toString())
        ) {
            return false
        }
        refreshReadableTip()
        if (SparseAwareFileLength.isSparsePartial(lastDeclaredLength, lastObservedLength)) {
            return false
        }
        val now = System.currentTimeMillis()
        val lastModified = queryLastModified(uri)
        if (lastModified != null) {
            val mtimeAge = now - lastModified
            if (mtimeAge in 0 until RECENT_MTIME_MS) {
                return false
            }
        }
        if (lastGrowthElapsedMs > 0L && now - lastGrowthElapsedMs < RECENT_GROWTH_WINDOW_MS) {
            return false
        }

        var lastLength = lastObservedLength
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < STABLE_DURATION_MS) {
            throwIfClosedOrInterrupted()
            sleepInterruptibly(POLL_INTERVAL_MS)
            try {
                reopenAt(uri, readPosition)
            } catch (_: IOException) {
                return false
            }
            val length = lastObservedLength
            if (SparseAwareFileLength.isSparsePartial(lastDeclaredLength, length)) {
                return false
            }
            if (length != lastLength) {
                return false
            }
            if (length != AssetFileDescriptor.UNKNOWN_LENGTH && length > readPosition) {
                return false
            }
            // Probe: if reopen positioned at readPosition still has a readable byte, not finished.
            val stream = inputStream
            if (stream != null &&
                !SparseAwareFileLength.isSparsePartial(lastDeclaredLength, length)
            ) {
                val probe = try {
                    stream.read()
                } catch (_: IOException) {
                    -1
                }
                if (probe >= 0) {
                    // Consumed one byte while probing — reopen at the original position next loop.
                    reopenAt(uri, readPosition)
                    return false
                }
            }
            val loopNow = System.currentTimeMillis()
            val loopMtime = queryLastModified(uri)
            if (loopMtime != null && loopNow - loopMtime in 0 until RECENT_MTIME_MS) {
                return false
            }
            if (lastGrowthElapsedMs > 0L &&
                loopNow - lastGrowthElapsedMs < RECENT_GROWTH_WINDOW_MS
            ) {
                return false
            }
            lastLength = length
        }
        // Stable EOF (including UNKNOWN_LENGTH) for STABLE_DURATION_MS.
        return !SparseAwareFileLength.isSparsePartial(lastDeclaredLength, lastObservedLength)
    }

    private fun openDescriptorAt(uri: Uri, position: Long) {
        synchronized(lock) {
            closeDescriptorQuietly()
            val afd = resolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Could not open file descriptor for: $uri")
            assetFileDescriptor = afd
            val stream = FileInputStream(afd.fileDescriptor)
            val startOffset = afd.startOffset
            val channel = stream.channel
            channel.position(startOffset + position)
            inputStream = stream

            val reported = afd.length
            val declared = if (reported != AssetFileDescriptor.UNKNOWN_LENGTH) {
                reported
            } else {
                try {
                    (channel.size() - startOffset).coerceAtLeast(0L)
                } catch (_: IOException) {
                    AssetFileDescriptor.UNKNOWN_LENGTH
                }
            }
            if (declared != AssetFileDescriptor.UNKNOWN_LENGTH && declared >= 0L) {
                lastDeclaredLength = declared
                val fd = currentFd()
                val readable = resolveReadableTip(declared, fd)
                noteObservedLength(readable)
            }
        }
    }

    private fun currentFd(): FileDescriptor? {
        return try {
            assetFileDescriptor?.parcelFileDescriptor?.fileDescriptor
                ?: assetFileDescriptor?.fileDescriptor
                ?: inputStream?.fd
        } catch (_: IOException) {
            null
        }
    }

    /** Re-probe SEEK_HOLE / zero-tail tip without reopening when an FD is already open. */
    private fun refreshReadableTip() {
        val declared = lastDeclaredLength
        if (declared < 0L || declared == AssetFileDescriptor.UNKNOWN_LENGTH) return
        val fd = currentFd() ?: return
        val readable = resolveReadableTip(declared, fd)
        noteObservedLength(readable)
    }

    private fun resolveReadableTip(declared: Long, fd: FileDescriptor?): Long {
        if (fd == null) return declared
        val hole = SparseAwareFileLength.sparseHoleReadableEnd(declared, fd)
        if (hole < declared) {
            cachedZeroTailTip = hole
            return hole
        }
        val prefer = preferZeroTailScan
        val now = System.currentTimeMillis()
        if (cachedZeroTailTip < 0L) {
            val tip = SparseAwareFileLength.readableEnd(
                declared,
                fd,
                preferZeroTailScan = prefer,
            )
            cachedZeroTailTip = tip
            lastTipProbeElapsedMs = now
            return if (tip < declared) tip else declared
        }
        if (now - lastTipProbeElapsedMs >= TIP_REPROBE_MS ||
            readPosition >= cachedZeroTailTip - SparseAwareFileLength.ZERO_TAIL_PROBE_BYTES
        ) {
            cachedZeroTailTip = SparseAwareFileLength.extendZeroPaddedTip(
                fd,
                cachedZeroTailTip,
                declared,
            )
            lastTipProbeElapsedMs = now
        }
        val tip = cachedZeroTailTip
        return if (tip in 0 until declared) tip else declared
    }

    private fun reopenAt(uri: Uri, position: Long) {
        openDescriptorAt(uri, position)
    }

    private fun noteObservedLength(length: Long) {
        if (length < 0L) return
        if (length > lastObservedLength) {
            lastObservedLength = length
            lastGrowthElapsedMs = System.currentTimeMillis()
        } else if (lastObservedLength < 0L) {
            lastObservedLength = length
        }
    }

    private fun closeDescriptorQuietly() {
        try {
            inputStream?.close()
        } catch (_: IOException) {
        } finally {
            inputStream = null
        }
        try {
            assetFileDescriptor?.close()
        } catch (_: IOException) {
        } finally {
            assetFileDescriptor = null
        }
    }

    /**
     * Best-effort last-modified for content URIs (Documents / MediaStore). Returns null when
     * unavailable so callers fall back to length-stability heuristics only.
     */
    private fun queryLastModified(uri: Uri): Long? {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        for (column in columns) {
            try {
                resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(column)
                        if (idx >= 0 && !cursor.isNull(idx)) {
                            var value = cursor.getLong(idx)
                            // MediaStore DATE_MODIFIED is seconds; Documents is millis.
                            if (column == MediaStore.MediaColumns.DATE_MODIFIED && value < 10_000_000_000L) {
                                value *= 1000L
                            }
                            if (value > 0L) return value
                        }
                    }
                }
            } catch (_: Exception) {
                // try next column
            }
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                uri.lastPathSegment
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        } finally {
            cursor?.close()
        }
    }

    private fun throwIfClosedOrInterrupted() {
        if (closed) throw InterruptedIOException("GrowingContentDataSource closed")
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("GrowingContentDataSource interrupted")
        }
    }

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val ex = InterruptedIOException("GrowingContentDataSource interrupted")
            ex.initCause(e)
            throw ex
        }
        if (closed) throw InterruptedIOException("GrowingContentDataSource closed")
    }

    companion object {
        private const val POLL_INTERVAL_MS = 50L
        /** Match [GrowingFileDataSource]: wait through downloader buffer pauses. */
        private const val STABLE_DURATION_MS = 30_000L
        private const val RECENT_GROWTH_WINDOW_MS = 15_000L
        private const val RECENT_MTIME_MS = 180_000L
        private const val CUE_SEEK_GAP_BYTES = 256L * 1024L
        private const val CUE_SEEK_FAIL_FAST_MS = 1_500L

        private const val TIP_REPROBE_MS = 400L

        fun looksPartialName(name: String?): Boolean =
            DownloadPathHeuristic.looksPartialFileName(name)
    }

    /** Creates [GrowingContentDataSource] instances. */
    class Factory(
        private val context: Context,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = GrowingContentDataSource(context)
    }
}
