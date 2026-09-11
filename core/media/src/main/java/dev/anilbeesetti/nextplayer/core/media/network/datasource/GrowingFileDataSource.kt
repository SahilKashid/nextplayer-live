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
 * [C.RESULT_END_OF_INPUT] once size and mtime have been stable for ~2s.
 *
 * ## Limitations
 * - Works best with streamable / growing-friendly containers (MKV, TS, many incomplete
 *   progressive downloads).
 * - MP4/MOV without an early `moov` atom may not play until that metadata is present
 *   (same as most players; VLC is more aggressive about demuxing incomplete MP4s).
 * - Reported duration and seekable range may update only as more media is parsed.
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
        while (true) {
            throwIfClosedOrInterrupted()
            val f = File(path)
            if (!f.exists()) {
                sleepInterruptibly(POLL_INTERVAL_MS)
                continue
            }
            val size = f.length()
            val mtime = f.lastModified()
            // Seek is OK at or before current EOF; past a finished file is an error.
            if (size >= position) {
                return
            }
            if (size == stableSize && mtime == stableMtime) {
                if (stableSince == 0L) {
                    stableSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - stableSince >= STABLE_DURATION_MS) {
                    throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                }
            } else {
                stableSize = size
                stableMtime = mtime
                stableSince = System.currentTimeMillis()
            }
            sleepInterruptibly(POLL_INTERVAL_MS)
        }
    }

    private fun waitUntilExists(path: String) {
        var absentSince = 0L
        while (true) {
            throwIfClosedOrInterrupted()
            if (File(path).exists()) return
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
     * [STABLE_DURATION_MS]. Download managers typically stop updating both when done.
     */
    private fun isDownloadFinished(file: File): Boolean {
        var lastSize = file.length()
        var lastMtime = file.lastModified()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < STABLE_DURATION_MS) {
            throwIfClosedOrInterrupted()
            sleepInterruptibly(POLL_INTERVAL_MS)
            val size = file.length()
            val mtime = file.lastModified()
            if (size != lastSize || mtime != lastMtime) {
                return false
            }
            if (size > readPosition) {
                return false
            }
            lastSize = size
            lastMtime = mtime
        }
        return file.length() <= readPosition
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
        private const val STABLE_DURATION_MS = 2000L

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
