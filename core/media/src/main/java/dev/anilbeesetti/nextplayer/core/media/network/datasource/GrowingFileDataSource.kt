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
 * [read] blocks (polling ~50ms) until more bytes appear — VLC-style wait through downloader
 * buffer pauses. It only returns [C.RESULT_END_OF_INPUT] once size and mtime have been stable
 * for ~30s, the name does not look like a partial download, mtime is older than ~3 minutes,
 * and nothing has grown within the recent-growth window.
 *
 * Tiny / empty files still open successfully (with [C.LENGTH_UNSET]) so the extractors / load
 * retry policy can wait for headers (e.g. MP4 `moov`) rather than failing at the DataSource.
 *
 * ## Sparse / zero-preallocated downloads
 * Many ADMs create the destination at **final size** immediately and fill it sequentially.
 * [File.length] then returns the full declared size while bytes past the download tip are
 * sparse holes **or** real zero bytes (1DM-style non-sparse preallocation). We use
 * [SparseAwareFileLength] (`SEEK_HOLE` + last-non-zero binary search) as the readable end and
 * poll/block when the tip has not advanced — never returning hole / padding zeros as media.
 * Download-manager path heuristics (`1DM`, `/Download/`, …) force zero-tail scanning even when
 * mtime is stale and SEEK_HOLE is useless.
 *
 * ## Limitations
 * - Works best with streamable / growing-friendly containers (MKV, TS, many incomplete
 *   progressive downloads).
 * - MP4/MOV without an early `moov` atom may need load retries until that metadata is present
 *   (see player `GrowingFileLoadErrorHandlingPolicy`).
 * - Reported duration and seekable range may update only as more media is parsed.
 * - Unresolvable `content://` URIs use [GrowingContentDataSource] instead.
 * - On API 24–25, SEEK_HOLE is unavailable; sparse tails fall back to zero-tail scanning.
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

    /** Wall-clock millis when readable end (download tip) last increased. */
    private var lastGrowthElapsedMs: Long = 0L
    private var lastSeenLength: Long = -1L

    /** Cached zero-tail / sparse tip when SEEK_HOLE is useless (1DM zeros). */
    private var cachedReadableTip: Long = -1L
    private var lastTipProbeElapsedMs: Long = 0L
    private var preferZeroTailScan: Boolean = false

    private val lock = Any()

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        val resolvedPath = resolvePath(dataSpec.uri)
            ?: throw IOException("GrowingFileDataSource requires a filesystem path: ${dataSpec.uri}")

        uri = dataSpec.uri
        path = resolvedPath
        preferZeroTailScan = DownloadPathHeuristic.looksLikeDownloadManagerPath(resolvedPath)
        cachedReadableTip = -1L
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
                noteLength(effectiveLength(resolvedPath))
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
            val declared = localFile.length()
            val lengthOnDisk = effectiveLength(path, declared)
            noteLength(lengthOnDisk)
            val available = lengthOnDisk - readPosition

            if (available > 0) {
                ensureFileReflectsLength(path, declared)
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

            // Tip reached but declared size still larger (sparse hole) or file still growing —
            // never return zeros from the hole; poll like a growing append.
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

    /**
     * Readable download tip: SEEK_HOLE when available, else last-non-zero (zero-preallocated)
     * with a cached tip that is cheaply extended on each poll.
     */
    private fun effectiveLength(path: String, declared: Long = File(path).length()): Long {
        if (declared <= 0L) return 0L
        val raf = file
        // Prefer in-place tip tracking on the open RAF (avoids reopening for every poll).
        if (raf != null) {
            return effectiveLengthWithRaf(raf, path, declared)
        }
        val prefer = preferZeroTailScan ||
            DownloadPathHeuristic.looksLikeDownloadManagerPath(path)
        return SparseAwareFileLength.readableEnd(path, declared, fd = null, preferZeroTailScan = prefer)
    }

    private fun effectiveLengthWithRaf(
        raf: RandomAccessFile,
        path: String,
        declared: Long,
    ): Long {
        val prefer = preferZeroTailScan ||
            DownloadPathHeuristic.looksLikeDownloadManagerPath(path)
        // SEEK_HOLE first (sparse ADM).
        val holeBased = try {
            SparseAwareFileLength.sparseHoleReadableEnd(path, declared, raf.fd)
        } catch (_: Exception) {
            declared
        }
        if (holeBased < declared) {
            cachedReadableTip = holeBased
            noteLength(holeBased)
            return holeBased
        }
        val now = System.currentTimeMillis()
        if (cachedReadableTip < 0L) {
            if (!prefer && !SparseAwareFileLength.quickTailIsAllZeros(raf, declared)) {
                return declared
            }
            cachedReadableTip = SparseAwareFileLength.zeroPaddedReadableEnd(raf, declared)
            lastTipProbeElapsedMs = now
        } else if (
            now - lastTipProbeElapsedMs >= TIP_REPROBE_MS ||
            readPosition >= cachedReadableTip - SparseAwareFileLength.ZERO_TAIL_PROBE_BYTES
        ) {
            cachedReadableTip = SparseAwareFileLength.extendZeroPaddedTip(
                raf,
                cachedReadableTip,
                declared,
            )
            lastTipProbeElapsedMs = now
        }
        val tip = cachedReadableTip
        return if (tip in 0 until declared) tip else declared
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
            val declared = f.length()
            val size = effectiveLength(path, declared)
            val mtime = f.lastModified()
            noteLength(size)
            // Seek is OK at or before current readable tip; past a finished file is an error.
            if (size >= position) {
                return
            }
            val now = System.currentTimeMillis()
            if (waitStarted == 0L) {
                waitStarted = now
            }
            val gap = position - size
            // Cue-style seeks land far past the current tip on incomplete MKVs. Do not wait the
            // full STABLE_DURATION_MS hoping for end cues — fail fast so load can proceed without
            // cue-seek (or retry). Still wait briefly for the next few KB of a cluster.
            // Sparse: declared may already be huge while tip is small — still fail-fast on gap.
            if (gap > CUE_SEEK_GAP_BYTES &&
                (looksPartialFileName(f.name) ||
                    DownloadPathHeuristic.looksLikeDownloadManagerPath(path) ||
                    isActivelyGrowing(mtime) ||
                    SparseAwareFileLength.isSparsePartial(declared, size)) &&
                now - waitStarted >= CUE_SEEK_FAIL_FAST_MS
            ) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            if (looksPartialFileName(f.name) ||
                DownloadPathHeuristic.looksLikeDownloadManagerPath(path) ||
                SparseAwareFileLength.isSparsePartial(declared, size)
            ) {
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
                noteLength(effectiveLength(path))
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
     * Treat the file as finished when its readable tip and mtime have not changed for
     * [STABLE_DURATION_MS], mtime is older than [RECENT_MTIME_MS], nothing grew within
     * [RECENT_GROWTH_WINDOW_MS], the name does not look like a partial download artifact, and
     * there is no sparse tail ([SparseAwareFileLength.isSparsePartial]).
     *
     * Download managers often pause longer than a few seconds between flushes; we wait through
     * those pauses (VLC-style) rather than declaring EOF mid-cluster.
     */
    private fun isDownloadFinished(file: File): Boolean {
        if (looksPartialFileName(file.name) ||
            DownloadPathHeuristic.looksLikeDownloadManagerPath(file.absolutePath)
        ) {
            return false
        }
        val path = file.absolutePath
        val declared = file.length()
        val readable = effectiveLength(path, declared)
        // Sparse preallocated tail: still downloading even if declared length is stable.
        if (SparseAwareFileLength.isSparsePartial(declared, readable)) {
            noteLength(readable)
            return false
        }
        val now = System.currentTimeMillis()
        val mtimeAge = now - file.lastModified()
        // Still being written / touched recently — keep waiting (never EOF on a fresh mtime).
        if (mtimeAge in 0 until RECENT_MTIME_MS) {
            return false
        }
        if (lastGrowthElapsedMs > 0L && now - lastGrowthElapsedMs < RECENT_GROWTH_WINDOW_MS) {
            return false
        }

        var lastSize = readable
        var lastMtime = file.lastModified()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < STABLE_DURATION_MS) {
            throwIfClosedOrInterrupted()
            sleepInterruptibly(POLL_INTERVAL_MS)
            val loopDeclared = file.length()
            val size = effectiveLength(path, loopDeclared)
            val mtime = file.lastModified()
            noteLength(size)
            if (SparseAwareFileLength.isSparsePartial(loopDeclared, size)) {
                return false
            }
            if (size != lastSize || mtime != lastMtime) {
                return false
            }
            if (size > readPosition) {
                return false
            }
            val loopNow = System.currentTimeMillis()
            if (loopNow - mtime in 0 until RECENT_MTIME_MS) {
                return false
            }
            // Growth within window (e.g. size bumped then stalled) still blocks EOF.
            if (lastGrowthElapsedMs > 0L &&
                loopNow - lastGrowthElapsedMs < RECENT_GROWTH_WINDOW_MS
            ) {
                return false
            }
            lastSize = size
            lastMtime = mtime
        }
        val finalDeclared = file.length()
        val finalReadable = effectiveLength(path, finalDeclared)
        if (SparseAwareFileLength.isSparsePartial(finalDeclared, finalReadable)) {
            return false
        }
        return finalReadable <= readPosition
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
     * Some platforms cache EOF on [RandomAccessFile]; if the on-disk declared length grew past
     * what we have read, reopen so newly appended / filled bytes are visible.
     */
    private fun ensureFileReflectsLength(path: String, declaredLength: Long) {
        val raf = file ?: return
        try {
            if (raf.length() < declaredLength) {
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
        /**
         * Idle stability before treating a growing file as finished.
         * Download managers often pause well beyond a few seconds between flushes — wait
         * through those (VLC-style) rather than EOF mid-cluster.
         */
        private const val STABLE_DURATION_MS = 30_000L
        /** Keep waiting if length grew this recently. */
        private const val RECENT_GROWTH_WINDOW_MS = 15_000L
        /** Keep waiting if lastModified is within this window (downloader still active). */
        private const val RECENT_MTIME_MS = 180_000L
        /** Far seeks (e.g. Matroska Cues near EOF) beyond current length + this gap fail fast. */
        private const val CUE_SEEK_GAP_BYTES = 256L * 1024L
        private const val CUE_SEEK_FAIL_FAST_MS = 1_500L
        private const val CUE_SEEK_FRESH_MTIME_MS = 20_000L

        /** Re-probe zero-tail tip at most this often while waiting past the tip. */
        private const val TIP_REPROBE_MS = 400L

        /** True when [name] looks like an in-progress download artifact. */
        fun looksPartialFileName(name: String?): Boolean =
            DownloadPathHeuristic.looksPartialFileName(name)

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
