package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
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
 * (see [GrowingFileDataSource] for shared finish heuristics).
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

    private var lastObservedLength: Long = -1L
    private var lastGrowthElapsedMs: Long = 0L
    private var displayName: String? = null

    private val lock = Any()

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        val openUri = dataSpec.uri
        if (!ContentResolver.SCHEME_CONTENT.equals(openUri.scheme, ignoreCase = true)) {
            throw IOException("GrowingContentDataSource requires content:// URI: $openUri")
        }

        uri = openUri
        displayName = queryDisplayName(openUri)
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
        while (true) {
            throwIfClosedOrInterrupted()
            try {
                openDescriptorAt(uri, 0L)
                val length = lastObservedLength
                if (position <= 0L) {
                    // Tiny / empty content still succeeds with LENGTH_UNSET at open.
                    return
                }
                if (length == AssetFileDescriptor.UNKNOWN_LENGTH || length >= position) {
                    // Seek target reachable (or unknown growing length); open() will position.
                    openDescriptorAt(uri, position)
                    return
                }
                if (length == stableLength) {
                    if (stableSince == 0L) {
                        stableSince = System.currentTimeMillis()
                    } else if (
                        System.currentTimeMillis() - stableSince >= STABLE_DURATION_MS &&
                        !looksPartialName(displayName)
                    ) {
                        throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                    }
                } else {
                    stableLength = length
                    stableSince = System.currentTimeMillis()
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
        if (looksPartialName(displayName) || looksPartialName(uri.lastPathSegment)) {
            return false
        }
        val now = System.currentTimeMillis()
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
            if (length != lastLength) {
                return false
            }
            if (length != AssetFileDescriptor.UNKNOWN_LENGTH && length > readPosition) {
                return false
            }
            // Probe: if reopen positioned at readPosition still has a readable byte, not finished.
            val stream = inputStream
            if (stream != null) {
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
            if (lastGrowthElapsedMs > 0L &&
                System.currentTimeMillis() - lastGrowthElapsedMs < RECENT_GROWTH_WINDOW_MS
            ) {
                return false
            }
            lastLength = length
        }
        // Stable EOF (including UNKNOWN_LENGTH) for STABLE_DURATION_MS.
        return true
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
            if (reported != AssetFileDescriptor.UNKNOWN_LENGTH) {
                noteObservedLength(reported)
            } else {
                // Fall back to channel size past start offset when available.
                try {
                    val size = (channel.size() - startOffset).coerceAtLeast(0L)
                    if (size > 0L) noteObservedLength(size)
                } catch (_: IOException) {
                    // ignore
                }
            }
        }
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
        private const val STABLE_DURATION_MS = 6_000L
        private const val RECENT_GROWTH_WINDOW_MS = 10_000L

        private val PARTIAL_SUFFIXES = listOf(
            ".part",
            ".crdownload",
            ".!ut",
            ".tmp",
            ".download",
            ".aria2",
            ".bc!",
        )

        fun looksPartialName(name: String?): Boolean {
            if (name.isNullOrEmpty()) return false
            val lower = name.lowercase()
            return PARTIAL_SUFFIXES.any { lower.endsWith(it) }
        }
    }

    /** Creates [GrowingContentDataSource] instances. */
    class Factory(
        private val context: Context,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = GrowingContentDataSource(context)
    }
}
