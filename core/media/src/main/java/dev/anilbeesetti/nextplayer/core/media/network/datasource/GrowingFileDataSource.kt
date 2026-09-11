package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * [DataSource] for a local file that may still be growing (e.g. an incomplete download).
 *
 * Unlike Media3 [androidx.media3.datasource.FileDataSource], [open] always returns
 * [C.LENGTH_UNSET] so ExoPlayer does not treat the then-current EOF as the end of the stream.
 * [read] blocks (polling ~50ms) until more bytes appear; it only returns
 * [C.RESULT_END_OF_INPUT] once size and mtime have been stable for ~6s, the name does not look
 * like a partial download, and nothing has grown within the last ~10s.
 *
 * Tiny / empty files still open successfully (with [C.LENGTH_UNSET]) so the extractors / load
 * retry policy can wait for headers (e.g. MP4 `moov`) rather than failing at the DataSource.
 *
 * ## Limitations
 * - Works best with streamable / growing-friendly containers (MKV, TS, many incomplete
 *   progressive downloads).
 * - MP4/MOV without an early `moov` atom may need load retries until that metadata is present
 *   (see player `GrowingFileLoadErrorHandlingPolicy`).
 * - Reported duration and seekable range may update only as more media is parsed.
 * - Unresolvable `content://` URIs use [GrowingContentDataSource] instead.
 *
 * See ExoPlayer issues #10472 / #7070.
 */
@UnstableApi
class GrowingFileDataSource : BaseDataSource(/* isNetwork = */ false) {

    private var uri: Uri? = null
    private var path: String? = null
    private var file: RandomAccessFile? = null
    private var readPosition: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    @Volatile
    private var closed = false

    /** Wall-clock millis when on-disk length last increased. */
    private var lastGrowthElapsedMs: Long = 0L
    private var lastSeenLength: Long = -1L

    private val lock = Any()

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        val resolvedPath = resolvePath(dataSpec.uri)
            ?: throw IOException("GrowingFileDataSource requires a filesystem path: ${dataSpec.uri}")

        uri = dataSpec.uri
        path = resolvedPath
        transferInitializing(dataSpec)

        try {
            waitUntilPositionAvailable(resolvedPath, dataSpec.position)
            openFileAt(resolvedPath, dataSpec.position)
            readPosition = dataSpec.position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                C.LENGTH_UNSET.toLong()
            }
            val existing = File(resolvedPath)
            if (existing.exists()) {
                noteLength(existing.length())
            }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Failed to open growing file $resolvedPath", e)
        }

        opened = true
        transferStarted(dataSpec)
        // Always unset so the player keeps reading while the download grows.
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val path = this.path ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }

        while (true) {
            throwIfClosedOrInterrupted()

            val localFile = File(path)
            val lengthOnDisk = localFile.length()
            noteLength(lengthOnDisk)
            val available = lengthOnDisk - readPosition

            if (available > 0) {
                ensureFileReflectsLength(path, lengthOnDisk)
                val raf = file ?: return C.RESULT_END_OF_INPUT
                val bytesRead = try {
                    raf.read(buffer, offset, minOf(toRead.toLong(), available).toInt())
                } catch (_: IOException) {
                    // File may have been replaced / truncated by the downloader; reopen once.
                    reopenAt(path, readPosition)
                    file!!.read(buffer, offset, minOf(toRead.toLong(), available).toInt())
                }
                if (bytesRead > 0) {
                    readPosition += bytesRead
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                        bytesRemaining -= bytesRead
                    }
                    bytesTransferred(bytesRead)
                    return bytesRead
                }
                // read() returned -1 despite length claiming data — reopen and retry.
                reopenAt(path, readPosition)
                continue
            }

            if (isDownloadFinished(localFile)) {
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
            path = null
            try {
                file?.close()
            } finally {
                file = null
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }

    private fun waitUntilPositionAvailable(path: String, position: Long) {
        if (position <= 0L) {
            waitUntilExists(path)
            return
        }
        var stableSize = -1L
        var stableMtime = -1L
        var stableSince = 0L
        var waitStarted = 0L
        while (true) {
            throwIfClosedOrInterrupted()
            val f = File(path)
            if (!f.exists()) {
                sleepInterruptibly(POLL_INTERVAL_MS)
                continue
            }
            val size = f.length()
            val mtime = f.lastModified()
            noteLength(size)
            // Seek is OK at or before current EOF; past a finished file is an error.
            if (size >= position) {
                return
            }
            val now = System.currentTimeMillis()
            if (waitStarted == 0L) {
                waitStarted = now
            }
            val gap = position - size
            // Cue-style seeks land far past the current EOF on incomplete MKVs. Do not wait the
            // full STABLE_DURATION_MS hoping for end cues — fail fast so load can proceed without
            // cue-seek (or retry). Still wait briefly for the next few KB of a cluster.
            if (gap > CUE_SEEK_GAP_BYTES &&
                (looksPartialFileName(f.name) || isActivelyGrowing(mtime)) &&
                now - waitStarted >= CUE_SEEK_FAIL_FAST_MS
            ) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            if (looksPartialFileName(f.name)) {
                stableSize = size
                stableMtime = mtime
                stableSince = now
                sleepInterruptibly(POLL_INTERVAL_MS)
                continue
            }
            if (size == stableSize && mtime == stableMtime) {
                if (stableSince == 0L) {
                    stableSince = now
                } else if (now - stableSince >= STABLE_DURATION_MS) {
                    throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                }
            } else {
                stableSize = size
                stableMtime = mtime
                stableSince = now
            }
            sleepInterruptibly(POLL_INTERVAL_MS)
        }
    }

    /** True when length recently grew or mtime is very fresh (downloader buffering). */
    private fun isActivelyGrowing(mtime: Long): Boolean {
        val now = System.currentTimeMillis()
        if (lastGrowthElapsedMs > 0L && now - lastGrowthElapsedMs < RECENT_GROWTH_WINDOW_MS) {
            return true
        }
        return now - mtime in 0 until CUE_SEEK_FRESH_MTIME_MS
    }

    private fun waitUntilExists(path: String) {
        var absentSince = 0L
        while (true) {
            throwIfClosedOrInterrupted()
            val f = File(path)
            if (f.exists()) {
                // Tiny / empty files are fine — LENGTH_UNSET lets sniff/retry wait for headers.
                noteLength(f.length())
                return
            }
            if (absentSince == 0L) {
                absentSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - absentSince >= STABLE_DURATION_MS) {
                throw FileNotFoundException(path)
            }
            sleepInterruptibly(POLL_INTERVAL_MS)
        }
    }

    /**
     * Treat the file as finished when its length and mtime have not changed for
     * [STABLE_DURATION_MS], nothing grew within [RECENT_GROWTH_WINDOW_MS], and the name does not
     * look like a partial download artifact.
     */
    private fun isDownloadFinished(file: File): Boolean {
        if (looksPartialFileName(file.name)) {
            return false
        }
        val now = System.currentTimeMillis()
        if (lastGrowthElapsedMs > 0L && now - lastGrowthElapsedMs < RECENT_GROWTH_WINDOW_MS) {
            return false
        }

        var lastSize = file.length()
        var lastMtime = file.lastModified()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < STABLE_DURATION_MS) {
            throwIfClosedOrInterrupted()
            sleepInterruptibly(POLL_INTERVAL_MS)
            val size = file.length()
            val mtime = file.lastModified()
            noteLength(size)
            if (size != lastSize || mtime != lastMtime) {
                return false
            }
            if (size > readPosition) {
                return false
            }
            // Growth within window (e.g. size bumped then stalled) still blocks EOF.
            if (lastGrowthElapsedMs > 0L &&
                System.currentTimeMillis() - lastGrowthElapsedMs < RECENT_GROWTH_WINDOW_MS
            ) {
                return false
            }
            lastSize = size
            lastMtime = mtime
        }
        return file.length() <= readPosition
    }

    private fun noteLength(length: Long) {
        if (length < 0L) return
        if (length > lastSeenLength) {
            lastSeenLength = length
            lastGrowthElapsedMs = System.currentTimeMillis()
        } else if (lastSeenLength < 0L) {
            lastSeenLength = length
        }
    }

    private fun openFileAt(path: String, position: Long) {
        synchronized(lock) {
            file?.close()
            val raf = RandomAccessFile(path, "r")
            raf.seek(position)
            file = raf
        }
    }

    private fun reopenAt(path: String, position: Long) {
        openFileAt(path, position)
    }

    /**
     * Some platforms cache EOF on [RandomAccessFile]; if the on-disk length grew past what we
     * have read, reopen so newly appended bytes are visible.
     */
    private fun ensureFileReflectsLength(path: String, lengthOnDisk: Long) {
        val raf = file ?: return
        try {
            if (raf.length() < lengthOnDisk) {
                reopenAt(path, readPosition)
            }
        } catch (_: IOException) {
            reopenAt(path, readPosition)
        }
    }

    private fun throwIfClosedOrInterrupted() {
        if (closed) throw InterruptedIOException("GrowingFileDataSource closed")
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("GrowingFileDataSource interrupted")
        }
    }

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val ex = InterruptedIOException("GrowingFileDataSource interrupted")
            ex.initCause(e)
            throw ex
        }
        if (closed) throw InterruptedIOException("GrowingFileDataSource closed")
    }

    companion object {
        private const val POLL_INTERVAL_MS = 50L
        /** Idle stability before treating a growing file as finished (was 2s; too short for downloader buffer pauses). */
        private const val STABLE_DURATION_MS = 6_000L
        private const val RECENT_GROWTH_WINDOW_MS = 10_000L
        /** Far seeks (e.g. Matroska Cues near EOF) beyond current length + this gap fail fast. */
        private const val CUE_SEEK_GAP_BYTES = 256L * 1024L
        private const val CUE_SEEK_FAIL_FAST_MS = 1_500L
        private const val CUE_SEEK_FRESH_MTIME_MS = 20_000L

        private val PARTIAL_SUFFIXES = listOf(
            ".part",
            ".crdownload",
            ".!ut",
            ".tmp",
            ".download",
            ".aria2",
            ".bc!",
        )

        /** True when [name] looks like an in-progress download artifact. */
        fun looksPartialFileName(name: String?): Boolean {
            if (name.isNullOrEmpty()) return false
            val lower = name.lowercase()
            return PARTIAL_SUFFIXES.any { lower.endsWith(it) }
        }

        /** Resolves `file://` URIs and raw filesystem paths. */
        fun resolvePath(uri: Uri): String? {
            val scheme = uri.scheme
            return when {
                scheme.isNullOrEmpty() -> uri.path ?: uri.toString().takeIf { it.startsWith("/") }
                scheme.equals("file", ignoreCase = true) -> uri.path
                else -> null
            }
        }
    }

    /** Creates [GrowingFileDataSource] instances. */
    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = GrowingFileDataSource()
    }
}
