package dev.anilbeesetti.nextplayer.feature.player.service

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Locates the nearest preceding Matroska/WebM Cluster element for demux sync after an
 * approximate byte seek.
 *
 * Cluster EBML ID: `1F 43 B6 75`.
 */
object MatroskaClusterFinder {

    /** Matroska Cluster element ID. */
    val CLUSTER_ID: ByteArray = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)

    const val DEFAULT_MAX_SCAN_BACK: Long = 2L * 1024L * 1024L

    /**
     * Scan backwards from `min(position, tip - 4)` within [maxScanBack] for [CLUSTER_ID].
     * Returns the marker offset, or `0` if none found.
     */
    fun findNearestClusterStart(
        data: ByteArray,
        position: Long,
        tip: Long = data.size.toLong(),
        maxScanBack: Long = DEFAULT_MAX_SCAN_BACK,
    ): Long {
        val effectiveTip = tip.coerceAtMost(data.size.toLong()).coerceAtLeast(0L)
        if (effectiveTip < CLUSTER_ID.size) return 0L
        val start = min(position, effectiveTip - CLUSTER_ID.size).coerceAtLeast(0L)
        val scanFrom = (start - maxScanBack).coerceAtLeast(0L)
        val found = scanBackwards(data, scanFrom.toInt(), start.toInt())
        return if (found >= 0) found.toLong() else 0L
    }

    /**
     * File-backed scan (opens a short-lived RAF). Returns marker offset or `0`.
     */
    fun findNearestClusterStart(
        file: File,
        position: Long,
        tip: Long,
        maxScanBack: Long = DEFAULT_MAX_SCAN_BACK,
    ): Long {
        if (!file.exists() || tip < CLUSTER_ID.size) return 0L
        return try {
            RandomAccessFile(file, "r").use { raf ->
                findNearestClusterStart(raf, position, tip, maxScanBack)
            }
        } catch (_: IOException) {
            0L
        }
    }

    /**
     * RAF-backed scan. Restores the previous file pointer afterwards.
     */
    fun findNearestClusterStart(
        raf: RandomAccessFile,
        position: Long,
        tip: Long,
        maxScanBack: Long = DEFAULT_MAX_SCAN_BACK,
    ): Long {
        if (tip < CLUSTER_ID.size) return 0L
        val start = min(position, tip - CLUSTER_ID.size).coerceAtLeast(0L)
        val scanFrom = (start - maxScanBack).coerceAtLeast(0L)
        val length = (start - scanFrom).toInt() + CLUSTER_ID.size
        if (length <= 0) return 0L
        val previous = try {
            raf.filePointer
        } catch (_: IOException) {
            -1L
        }
        return try {
            val buf = ByteArray(length)
            raf.seek(scanFrom)
            var read = 0
            while (read < length) {
                val n = raf.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            if (read < CLUSTER_ID.size) return 0L
            val endInclusive = min(start - scanFrom, (read - CLUSTER_ID.size).toLong()).toInt()
            val relative = scanBackwards(buf, 0, endInclusive)
            if (relative >= 0) scanFrom + relative else 0L
        } catch (_: IOException) {
            0L
        } finally {
            if (previous >= 0L) {
                try {
                    raf.seek(previous)
                } catch (_: IOException) {
                }
            }
        }
    }

    /**
     * Search [data] for Cluster ID with candidate starts in
     * `[startInclusive, endInclusive]` (inclusive), scanning high→low.
     * @return index of marker within [data], or `-1` if none
     */
    private fun scanBackwards(data: ByteArray, startInclusive: Int, endInclusive: Int): Int {
        if (data.size < CLUSTER_ID.size) return -1
        val last = endInclusive.coerceAtMost(data.size - CLUSTER_ID.size)
        if (last < startInclusive) return -1
        var i = last
        while (i >= startInclusive) {
            if (data[i] == CLUSTER_ID[0] &&
                data[i + 1] == CLUSTER_ID[1] &&
                data[i + 2] == CLUSTER_ID[2] &&
                data[i + 3] == CLUSTER_ID[3]
            ) {
                return i
            }
            i--
        }
        return -1
    }
}
