package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Resolves the **readable** end of a local file that may be sparse / preallocated, or
 * zero-padded (non-sparse preallocation).
 *
 * Downloaders often create the destination at the **final** size immediately
 * (fallocate / truncate) and fill it sequentially. [java.io.File.length] then returns the full
 * declared size while bytes past the download tip are either:
 * - sparse holes — detectable via SEEK_HOLE on API 26+, or
 * - real zero bytes — SEEK_HOLE returns EOF; we binary-search the last non-zero byte
 *   when the last ~256KiB is all zeros (content trigger, any path).
 *
 * Media3 must not treat those holes / zero tails as valid media.
 *
 * The file descriptor / RAF position is always restored after probing.
 */
object SparseAwareFileLength {

    /**
     * Linux/Android [lseek(2)] `SEEK_HOLE` — not always present on older android.jar stubs
     * ([OsConstants.SEEK_HOLE] may be missing), so we use the numeric value.
     */
    private const val SEEK_HOLE = 4

    /** Probe window for zero-tail binary search (~20 reads for an ~800MB file). */
    const val ZERO_TAIL_PROBE_BYTES = 64 * 1024

    /** Quick check of the last N bytes before deciding to run a full zero-tail scan. */
    const val ZERO_TAIL_QUICK_CHECK_BYTES = 256 * 1024

    /** Skip zero-tail work on tiny files. */
    const val ZERO_TAIL_MIN_DECLARED_BYTES = 1L * 1024L * 1024L

    /**
     * Pure selection logic (unit-testable without Android Os):
     * if `0 < holeOffset < declaredLength`, the first hole / tip is the readable end; otherwise
     * use [declaredLength] (no hole, hole at EOF, or invalid probe).
     *
     * When [tailHasRealData] is true and the hole sits **before** the last
     * [ZERO_TAIL_QUICK_CHECK_BYTES], treat the file as finished: a sequential
     * download that has written the tail cannot still have a hole that far from
     * EOF, so SEEK_HOLE is a false positive on a complete file.
     * A hole *inside* the tail window is still the real sequential tip.
     */
    fun chooseReadableEnd(
        declaredLength: Long,
        holeOffset: Long,
        tailHasRealData: Boolean = false,
    ): Long {
        if (declaredLength <= 0L) return 0L
        if (tailHasRealData &&
            holeOffset > 0L &&
            holeOffset < declaredLength - ZERO_TAIL_QUICK_CHECK_BYTES
        ) {
            return declaredLength
        }
        return if (holeOffset > 0L && holeOffset < declaredLength) {
            holeOffset
        } else {
            declaredLength
        }
    }

    /** True when the file still has a sparse or zero-padded tail past the download tip. */
    fun isSparsePartial(declaredLength: Long, readableEnd: Long): Boolean =
        declaredLength > 0L && readableEnd >= 0L && readableEnd < declaredLength

    /**
     * True when the last [ZERO_TAIL_QUICK_CHECK_BYTES] of a large file contain a
     * non-zero byte (cues / real media). Small files return false (unknown).
     */
    fun tailHasRealData(raf: RandomAccessFile, declaredLength: Long): Boolean {
        if (declaredLength < ZERO_TAIL_MIN_DECLARED_BYTES) return false
        return !quickTailIsAllZeros(raf, declaredLength)
    }

    fun tailHasRealData(path: String, declaredLength: Long, fd: FileDescriptor?): Boolean {
        if (declaredLength < ZERO_TAIL_MIN_DECLARED_BYTES) return false
        return try {
            if (fd != null) {
                tailHasRealData(declaredLength, fd)
            } else {
                RandomAccessFile(path, "r").use { raf ->
                    tailHasRealData(raf, declaredLength)
                }
            }
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun tailHasRealData(declaredLength: Long, fd: FileDescriptor?): Boolean {
        if (declaredLength < ZERO_TAIL_MIN_DECLARED_BYTES || fd == null) return false
        return try {
            !quickTailIsAllZerosFd(fd, declaredLength)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Readable end for [path] / optional open [fd].
     *
     * Order: SEEK_HOLE (API 26+) → ignore a far hole when the tail already has
     * real bytes (finished file) → optional zero-tail scan when [preferZeroTailScan]
     * or the last [ZERO_TAIL_QUICK_CHECK_BYTES] are all zeros on a large file.
     *
     * @param path filesystem path (used to open a temporary FD / RAF when [fd] is null)
     * @param declaredLength [java.io.File.length] / AFD reported length
     * @param fd open file descriptor whose position will be preserved; may be null
     * @param preferZeroTailScan force last-non-zero scan (tests / callers that already know the tail is zeros)
     */
    fun readableEnd(
        path: String,
        declaredLength: Long,
        fd: FileDescriptor?,
        preferZeroTailScan: Boolean = false,
    ): Long {
        if (declaredLength <= 0L) return 0L
        val holeBased = holeReadableEnd(path, declaredLength, fd)
        val tailData = !preferZeroTailScan && tailHasRealData(path, declaredLength, fd)
        val chosen = chooseReadableEnd(declaredLength, holeBased, tailData)
        if (tailData && chosen >= declaredLength) return declaredLength
        if (chosen < declaredLength) return chosen
        return zeroTailOrDeclared(path, declaredLength, fd, preferZeroTailScan)
    }

    /**
     * Same as [readableEnd] when only an open [fd] is available (e.g. content AFD).
     */
    fun readableEnd(
        declaredLength: Long,
        fd: FileDescriptor?,
        preferZeroTailScan: Boolean = false,
    ): Long {
        if (declaredLength <= 0L) return 0L
        if (fd == null) return declaredLength
        val holeBased = holeReadableEndWithFd(declaredLength, fd)
        val tailData = !preferZeroTailScan && tailHasRealData(declaredLength, fd)
        val chosen = chooseReadableEnd(declaredLength, holeBased, tailData)
        if (tailData && chosen >= declaredLength) return declaredLength
        if (chosen < declaredLength) return chosen
        return zeroTailOrDeclaredWithFd(declaredLength, fd, preferZeroTailScan)
    }

    /**
     * Binary-search / block-probe for the last non-zero byte (exclusive end = download tip)
     * under the sequential-prefix assumption used by zero-preallocated downloads.
     *
     * Restores [raf] file pointer.
     */
    fun zeroPaddedReadableEnd(raf: RandomAccessFile, declaredLength: Long): Long {
        if (declaredLength <= 0L) return 0L
        val saved = raf.filePointer
        return try {
            findZeroPaddedTip(raf, declaredLength)
        } finally {
            try {
                raf.seek(saved)
            } catch (_: IOException) {
            }
        }
    }

    /**
     * From a previously known [knownTip], check whether the download has written past it.
     * Cheap on the steady-state poll path (one probe at the tip); re-scans forward when data
     * appears.
     */
    fun extendZeroPaddedTip(raf: RandomAccessFile, knownTip: Long, declaredLength: Long): Long {
        if (declaredLength <= 0L) return 0L
        if (knownTip < 0L) return zeroPaddedReadableEnd(raf, declaredLength)
        if (knownTip >= declaredLength) return declaredLength
        val saved = raf.filePointer
        return try {
            val probeEnd = min(knownTip + ZERO_TAIL_PROBE_BYTES, declaredLength)
            if (!windowHasData(raf, knownTip, probeEnd)) {
                knownTip
            } else {
                // Tip advanced — find new end from knownTip forward (still sequential).
                findZeroPaddedTipFrom(raf, knownTip, declaredLength)
            }
        } finally {
            try {
                raf.seek(saved)
            } catch (_: IOException) {
            }
        }
    }

    /** True when the last [tailBytes] of the file are all zeros (quick incomplete check). */
    fun quickTailIsAllZeros(
        raf: RandomAccessFile,
        declaredLength: Long,
        tailBytes: Int = ZERO_TAIL_QUICK_CHECK_BYTES,
    ): Boolean {
        if (declaredLength < ZERO_TAIL_MIN_DECLARED_BYTES) return false
        val saved = raf.filePointer
        return try {
            val start = maxOf(0L, declaredLength - tailBytes)
            !windowHasData(raf, start, declaredLength)
        } finally {
            try {
                raf.seek(saved)
            } catch (_: IOException) {
            }
        }
    }

    /**
     * SEEK_HOLE-only readable end (no zero-tail scan). Use when the caller caches / extends
     * the zero-padded tip separately on the hot poll path.
     */
    fun sparseHoleReadableEnd(path: String, declaredLength: Long, fd: FileDescriptor?): Long =
        holeReadableEnd(path, declaredLength, fd)

    fun sparseHoleReadableEnd(declaredLength: Long, fd: FileDescriptor?): Long {
        if (declaredLength <= 0L) return 0L
        if (fd == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return declaredLength
        return holeReadableEndWithFd(declaredLength, fd)
    }

    private fun holeReadableEnd(path: String, declaredLength: Long, fd: FileDescriptor?): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return declaredLength
        }
        if (fd != null) {
            return holeReadableEndWithFd(declaredLength, fd)
        }
        return try {
            RandomAccessFile(path, "r").use { raf ->
                holeReadableEndWithFd(declaredLength, raf.fd)
            }
        } catch (_: IOException) {
            declaredLength
        } catch (_: RuntimeException) {
            declaredLength
        }
    }

    private fun holeReadableEndWithFd(declaredLength: Long, fd: FileDescriptor): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return declaredLength
        }
        return try {
            val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
            try {
                val hole = Os.lseek(fd, 0L, SEEK_HOLE)
                // Do not collapse tip to 0 via chooseReadableEnd's hole==0 branch for SEEK_HOLE:
                // hole at 0 can mean "no data extent" OR probe quirk; prefer declared when hole==0.
                if (hole > 0L && hole < declaredLength) hole else declaredLength
            } finally {
                Os.lseek(fd, cur, OsConstants.SEEK_SET)
            }
        } catch (_: ErrnoException) {
            declaredLength
        } catch (_: Exception) {
            declaredLength
        }
    }

    private fun zeroTailOrDeclared(
        path: String,
        declaredLength: Long,
        fd: FileDescriptor?,
        preferZeroTailScan: Boolean,
    ): Long {
        if (declaredLength < ZERO_TAIL_MIN_DECLARED_BYTES && !preferZeroTailScan) {
            return declaredLength
        }
        return try {
            RandomAccessFile(path, "r").use { raf ->
                if (!preferZeroTailScan && !quickTailIsAllZeros(raf, declaredLength)) {
                    return declaredLength
                }
                val tip = zeroPaddedReadableEnd(raf, declaredLength)
                if (tip < declaredLength) tip else declaredLength
            }
        } catch (_: IOException) {
            declaredLength
        } catch (_: RuntimeException) {
            declaredLength
        }
    }

    private fun zeroTailOrDeclaredWithFd(
        declaredLength: Long,
        fd: FileDescriptor,
        preferZeroTailScan: Boolean,
    ): Long {
        if (declaredLength < ZERO_TAIL_MIN_DECLARED_BYTES && !preferZeroTailScan) {
            return declaredLength
        }
        return try {
            val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
            try {
                if (!preferZeroTailScan && !quickTailIsAllZerosFd(fd, declaredLength)) {
                    return declaredLength
                }
                val tip = findZeroPaddedTipFd(fd, 0L, declaredLength)
                if (tip < declaredLength) tip else declaredLength
            } finally {
                Os.lseek(fd, cur, OsConstants.SEEK_SET)
            }
        } catch (_: ErrnoException) {
            declaredLength
        } catch (_: Exception) {
            declaredLength
        }
    }

    /**
     * Best-effort zero-tail tip using only an open [fd] (content AFD). Restores FD position.
     */
    fun zeroPaddedReadableEnd(fd: FileDescriptor, declaredLength: Long): Long {
        if (declaredLength <= 0L) return 0L
        return try {
            val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
            try {
                findZeroPaddedTipFd(fd, 0L, declaredLength)
            } finally {
                Os.lseek(fd, cur, OsConstants.SEEK_SET)
            }
        } catch (_: ErrnoException) {
            declaredLength
        } catch (_: Exception) {
            declaredLength
        }
    }

    fun extendZeroPaddedTip(fd: FileDescriptor, knownTip: Long, declaredLength: Long): Long {
        if (declaredLength <= 0L) return 0L
        if (knownTip < 0L) return zeroPaddedReadableEnd(fd, declaredLength)
        if (knownTip >= declaredLength) return declaredLength
        return try {
            val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
            try {
                val probeEnd = min(knownTip + ZERO_TAIL_PROBE_BYTES, declaredLength)
                if (!windowHasDataFd(fd, knownTip, probeEnd)) {
                    knownTip
                } else {
                    findZeroPaddedTipFd(fd, knownTip, declaredLength)
                }
            } finally {
                Os.lseek(fd, cur, OsConstants.SEEK_SET)
            }
        } catch (_: ErrnoException) {
            knownTip
        } catch (_: Exception) {
            knownTip
        }
    }

    private fun quickTailIsAllZerosFd(
        fd: FileDescriptor,
        declaredLength: Long,
        tailBytes: Int = ZERO_TAIL_QUICK_CHECK_BYTES,
    ): Boolean {
        if (declaredLength < ZERO_TAIL_MIN_DECLARED_BYTES) return false
        val start = maxOf(0L, declaredLength - tailBytes)
        // Probe failure (Os.read unsupported / EIO) must NOT look like an all-zero tail,
        // or finished content:// Open-with URIs collapse tip→0 and hang on Growing*.
        return when (val probe = windowHasDataFdOrNull(fd, start, declaredLength)) {
            null -> false
            else -> !probe
        }
    }

    private fun findZeroPaddedTipFd(
        fd: FileDescriptor,
        searchFrom: Long,
        declaredLength: Long,
    ): Long {
        if (declaredLength <= 0L) return 0L
        val from = searchFrom.coerceIn(0L, declaredLength)
        val probe = ZERO_TAIL_PROBE_BYTES.toLong()
        val lastStart = maxOf(from, declaredLength - probe)
        if (windowHasDataFd(fd, lastStart, declaredLength)) {
            return findLastNonZeroExclusiveFd(fd, lastStart, declaredLength)
        }
        var lo = from
        var hi = declaredLength
        while (hi - lo > probe) {
            val mid = (lo + hi) ushr 1
            if (windowHasDataFd(fd, mid, min(mid + probe, declaredLength))) {
                lo = mid
            } else {
                hi = mid
            }
        }
        if (lo == from && !windowHasDataFd(fd, from, min(from + probe, declaredLength))) {
            return if (from == 0L) 0L else from
        }
        return findLastNonZeroExclusiveFd(fd, lo, hi)
    }

    private fun findLastNonZeroExclusiveFd(fd: FileDescriptor, start: Long, end: Long): Long {
        if (end <= start) return start
        var pos = end
        val bufSize = min(ZERO_TAIL_PROBE_BYTES.toLong(), end - start).toInt().coerceAtLeast(1)
        val buf = ByteArray(bufSize)
        val bb = java.nio.ByteBuffer.wrap(buf)
        while (pos > start) {
            val readStart = maxOf(start, pos - buf.size)
            val len = (pos - readStart).toInt()
            Os.lseek(fd, readStart, OsConstants.SEEK_SET)
            bb.clear()
            bb.limit(len)
            var got = 0
            while (got < len) {
                val n = Os.read(fd, bb)
                if (n <= 0) break
                got += n
            }
            for (i in got - 1 downTo 0) {
                if (buf[i] != 0.toByte()) {
                    return readStart + i + 1
                }
            }
            pos = readStart
        }
        return start
    }

    private fun windowHasDataFd(fd: FileDescriptor, start: Long, end: Long): Boolean =
        windowHasDataFdOrNull(fd, start, end) == true

    /**
     * @return true if a non-zero byte was seen, false if the window is all zeros,
     *   null if the probe could not be completed (FD not readable via Os.read).
     */
    private fun windowHasDataFdOrNull(fd: FileDescriptor, start: Long, end: Long): Boolean? {
        if (end <= start) return false
        val bufSize = min(ZERO_TAIL_PROBE_BYTES.toLong(), end - start).toInt().coerceAtLeast(1)
        val buf = ByteArray(bufSize)
        val bb = java.nio.ByteBuffer.wrap(buf)
        var pos = start
        var readAnything = false
        while (pos < end) {
            val toRead = min(buf.size.toLong(), end - pos).toInt()
            try {
                Os.lseek(fd, pos, OsConstants.SEEK_SET)
                bb.clear()
                bb.limit(toRead)
                var got = 0
                while (got < toRead) {
                    val n = Os.read(fd, bb)
                    if (n <= 0) {
                        // EOF / error before filling the window.
                        return if (readAnything || got > 0) false else null
                    }
                    got += n
                    readAnything = true
                }
                for (i in 0 until got) {
                    if (buf[i] != 0.toByte()) return true
                }
                pos += got
            } catch (_: ErrnoException) {
                return null
            } catch (_: Exception) {
                return null
            }
        }
        return false
    }

    /**
     * Binary search for the exclusive end of the non-zero prefix (sequential download tip).
     */
    internal fun findZeroPaddedTip(raf: RandomAccessFile, declaredLength: Long): Long {
        return findZeroPaddedTipFrom(raf, 0L, declaredLength)
    }

    internal fun findZeroPaddedTipFrom(
        raf: RandomAccessFile,
        searchFrom: Long,
        declaredLength: Long,
    ): Long {
        if (declaredLength <= 0L) return 0L
        val from = searchFrom.coerceIn(0L, declaredLength)
        val probe = ZERO_TAIL_PROBE_BYTES.toLong()

        // Quick: last window has data → refine last non-zero within it.
        val lastStart = maxOf(from, declaredLength - probe)
        if (windowHasData(raf, lastStart, declaredLength)) {
            return findLastNonZeroExclusive(raf, lastStart, declaredLength)
        }

        // Last window all zeros — binary search under sequential-prefix assumption.
        var lo = from
        var hi = declaredLength
        while (hi - lo > probe) {
            val mid = (lo + hi) ushr 1
            if (windowHasData(raf, mid, min(mid + probe, declaredLength))) {
                lo = mid
            } else {
                hi = mid
            }
        }
        if (lo == from && !windowHasData(raf, from, min(from + probe, declaredLength))) {
            // No data in the first probe of the search range.
            // If searching from 0, file may be all zeros.
            if (from == 0L) return 0L
            return from
        }
        return findLastNonZeroExclusive(raf, lo, hi)
    }

    /** Exclusive end (= index after last non-zero) in [start, end). */
    internal fun findLastNonZeroExclusive(raf: RandomAccessFile, start: Long, end: Long): Long {
        if (end <= start) return start
        var pos = end
        val bufSize = min(ZERO_TAIL_PROBE_BYTES.toLong(), end - start).toInt().coerceAtLeast(1)
        val buf = ByteArray(bufSize)
        while (pos > start) {
            val readStart = maxOf(start, pos - buf.size)
            val len = (pos - readStart).toInt()
            raf.seek(readStart)
            var off = 0
            while (off < len) {
                val n = raf.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            for (i in off - 1 downTo 0) {
                if (buf[i] != 0.toByte()) {
                    return readStart + i + 1
                }
            }
            pos = readStart
        }
        return start
    }

    internal fun windowHasData(raf: RandomAccessFile, start: Long, end: Long): Boolean {
        if (end <= start) return false
        val bufSize = min(ZERO_TAIL_PROBE_BYTES.toLong(), end - start).toInt().coerceAtLeast(1)
        val buf = ByteArray(bufSize)
        var pos = start
        while (pos < end) {
            val toRead = min(buf.size.toLong(), end - pos).toInt()
            raf.seek(pos)
            var off = 0
            while (off < toRead) {
                val n = raf.read(buf, off, toRead - off)
                if (n < 0) return false
                off += n
            }
            for (i in 0 until off) {
                if (buf[i] != 0.toByte()) return true
            }
            pos += off
        }
        return false
    }
}
