package dev.anilbeesetti.nextplayer.feature.player.service

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Locates the nearest preceding Matroska/WebM Cluster element for demux sync after an
 * approximate byte seek.
 *
 * Cluster EBML ID: `1F 43 B6 75`. Candidates are validated by requiring a plausible EBML size
 * varint immediately after the ID (first size byte ≠ 0 / has a length mask bit) so frame-data
 * false positives and zero-padded tips (`No valid varint length mask`) are rejected.
 * Starts within the tip safety margin are skipped (incomplete trailing cluster).
 */
object MatroskaClusterFinder {

    /** Matroska Cluster element ID. */
    val CLUSTER_ID: ByteArray = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)

    /** Wider window is safe once size-varint validation reduces false positives. */
    const val DEFAULT_MAX_SCAN_BACK: Long = 4L * 1024L * 1024L

    /**
     * Scan backwards from `min(position, tip - 4)` within [maxScanBack] for a **validated**
     * [CLUSTER_ID]. Returns the marker offset, or `0` if none found (never an unvalidated hit).
     */
    fun findNearestClusterStart(
        data: ByteArray,
        position: Long,
        tip: Long = data.size.toLong(),
        maxScanBack: Long = DEFAULT_MAX_SCAN_BACK,
    ): Long {
        val effectiveTip = tip.coerceAtMost(data.size.toLong()).coerceAtLeast(0L)
        if (effectiveTip < CLUSTER_ID.size + 1L) return 0L
        val margin = ApproximateByteSeekMap.safetyMarginFor(effectiveTip)
        val maxStart = (effectiveTip - CLUSTER_ID.size - 1L)
            .coerceAtMost(effectiveTip - margin - 1L)
            .coerceAtLeast(-1L)
        if (maxStart < 0L) return 0L
        val start = min(position, maxStart).coerceAtLeast(0L)
        val scanFrom = (start - maxScanBack).coerceAtLeast(0L)
        val found = scanBackwards(data, scanFrom.toInt(), start.toInt(), effectiveTip)
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
        if (!file.exists() || tip < CLUSTER_ID.size + 1L) return 0L
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
        if (tip < CLUSTER_ID.size + 1L) return 0L
        val margin = ApproximateByteSeekMap.safetyMarginFor(tip)
        val maxStart = (tip - CLUSTER_ID.size - 1L)
            .coerceAtMost(tip - margin - 1L)
            .coerceAtLeast(-1L)
        if (maxStart < 0L) return 0L
        val start = min(position, maxStart).coerceAtLeast(0L)
        val scanFrom = (start - maxScanBack).coerceAtLeast(0L)
        // Read through ID + at least one size byte past [start].
        val length = (start - scanFrom).toInt() + CLUSTER_ID.size + 1
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
            if (read < CLUSTER_ID.size + 1) return 0L
            val endInclusive = min(start - scanFrom, (read - CLUSTER_ID.size - 1).toLong()).toInt()
            val relative = scanBackwards(buf, 0, endInclusive, tip - scanFrom)
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
     * Only returns candidates with a valid following EBML size varint.
     * @return index of marker within [data], or `-1` if none
     */
    private fun scanBackwards(
        data: ByteArray,
        startInclusive: Int,
        endInclusive: Int,
        tipRelative: Long,
    ): Int {
        if (data.size < CLUSTER_ID.size + 1) return -1
        val last = endInclusive.coerceAtMost(data.size - CLUSTER_ID.size - 1)
        if (last < startInclusive) return -1
        var i = last
        while (i >= startInclusive) {
            if (data[i] == CLUSTER_ID[0] &&
                data[i + 1] == CLUSTER_ID[1] &&
                data[i + 2] == CLUSTER_ID[2] &&
                data[i + 3] == CLUSTER_ID[3] &&
                isValidEbmlSizeVarint(data, i + CLUSTER_ID.size) &&
                !isWithinTipSafetyMargin(i.toLong(), tipRelative)
            ) {
                return i
            }
            i--
        }
        return -1
    }

    /**
     * EBML VINT length mask: first size byte must be non-zero (a leading 1-bit encodes length).
     * All-zero triggers Media3 `No valid varint length mask found`.
     */
    fun isValidEbmlSizeVarint(data: ByteArray, sizeOffset: Int): Boolean {
        if (sizeOffset < 0 || sizeOffset >= data.size) return false
        val first = data[sizeOffset].toInt() and 0xFF
        return first != 0
    }

    /** True when [candidateStart] lies in the unfinished edge near [tip]. */
    fun isWithinTipSafetyMargin(candidateStart: Long, tip: Long): Boolean {
        if (tip <= 0L || candidateStart < 0L) return true
        val margin = ApproximateByteSeekMap.safetyMarginFor(tip)
        if (margin <= 0L) return candidateStart + CLUSTER_ID.size + 1L > tip
        return candidateStart >= tip - margin
    }
}
