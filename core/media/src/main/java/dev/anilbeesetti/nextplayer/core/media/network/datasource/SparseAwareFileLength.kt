package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Resolves the **readable** end of a local file that may be sparse / preallocated.
 *
 * Advanced download managers often create the destination at the **final** size immediately
 * (fallocate / truncate) and fill it sequentially. [java.io.File.length] then returns the full
 * declared size while bytes past the download tip are sparse holes (zeros). Media3 must not
 * treat those holes as valid media.
 *
 * On API 26+, [Os.lseek] with SEEK_HOLE (constant 4) from offset 0 returns the end of the first
 * data extent — for sequential sparse writes that is the real downloaded tip. Below API 26
 * (minSdk 24), SEEK_HOLE is unavailable and we fall back to the declared length (limitation).
 *
 * The file descriptor position is always restored after probing.
 */
object SparseAwareFileLength {

    /**
     * Linux/Android [lseek(2)] `SEEK_HOLE` — not always present on older android.jar stubs
     * ([OsConstants.SEEK_HOLE] may be missing), so we use the numeric value.
     */
    private const val SEEK_HOLE = 4

    /**
     * Pure selection logic (unit-testable without Android Os):
     * if `0 < holeOffset < declaredLength`, the first hole is the readable end; otherwise use
     * [declaredLength] (no hole, hole at EOF, or invalid probe).
     */
    fun chooseReadableEnd(declaredLength: Long, holeOffset: Long): Long {
        if (declaredLength <= 0L) return 0L
        return if (holeOffset > 0L && holeOffset < declaredLength) {
            holeOffset
        } else {
            declaredLength
        }
    }

    /** True when the file still has a sparse tail past the download tip. */
    fun isSparsePartial(declaredLength: Long, readableEnd: Long): Boolean =
        declaredLength > 0L && readableEnd >= 0L && readableEnd < declaredLength

    /**
     * Readable end for [path] / optional open [fd].
     *
     * @param path filesystem path (used to open a temporary FD when [fd] is null)
     * @param declaredLength [java.io.File.length] / AFD reported length
     * @param fd open file descriptor whose position will be preserved; may be null
     */
    fun readableEnd(path: String, declaredLength: Long, fd: FileDescriptor?): Long {
        if (declaredLength <= 0L) return 0L
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // SEEK_HOLE requires API 26; minSdk 24 keeps declared-length behavior.
            return declaredLength
        }
        if (fd != null) {
            return readableEndWithFd(declaredLength, fd)
        }
        return try {
            RandomAccessFile(path, "r").use { raf ->
                readableEndWithFd(declaredLength, raf.fd)
            }
        } catch (_: IOException) {
            declaredLength
        } catch (_: RuntimeException) {
            declaredLength
        }
    }

    /**
     * Same as [readableEnd] when only an open [fd] is available (e.g. content AFD).
     */
    fun readableEnd(declaredLength: Long, fd: FileDescriptor?): Long {
        if (declaredLength <= 0L) return 0L
        if (fd == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return declaredLength
        }
        return readableEndWithFd(declaredLength, fd)
    }

    private fun readableEndWithFd(declaredLength: Long, fd: FileDescriptor): Long {
        return try {
            val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
            try {
                val hole = Os.lseek(fd, 0L, SEEK_HOLE)
                chooseReadableEnd(declaredLength, hole)
            } finally {
                Os.lseek(fd, cur, OsConstants.SEEK_SET)
            }
        } catch (_: ErrnoException) {
            declaredLength
        } catch (_: Exception) {
            declaredLength
        }
    }
}
