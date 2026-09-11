package dev.anilbeesetti.nextplayer.feature.player.service

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaClusterFinderTest {

    /** Cluster ID + a valid 1-byte EBML size varint (non-zero length mask). */
    private fun clusterWithSize(sizeByte: Byte = 0x81.toByte()): ByteArray =
        MatroskaClusterFinder.CLUSTER_ID + byteArrayOf(sizeByte)

    @Test
    fun findNearestClusterStart_inSyntheticBuffer_findsMarker() {
        val prefix = ByteArray(100) { 0x11 }
        val cluster = clusterWithSize()
        val suffix = ByteArray(50) { 0x22 }
        val data = prefix + cluster + suffix

        val found = MatroskaClusterFinder.findNearestClusterStart(
            data = data,
            position = (prefix.size + cluster.size + 20).toLong(),
            tip = data.size.toLong(),
        )
        assertEquals(prefix.size.toLong(), found)
    }

    @Test
    fun findNearestClusterStart_returnsZeroWhenMissing() {
        val data = ByteArray(200) { 0x55 }
        val found = MatroskaClusterFinder.findNearestClusterStart(
            data = data,
            position = 150L,
            tip = data.size.toLong(),
        )
        assertEquals(0L, found)
    }

    @Test
    fun findNearestClusterStart_picksNearestPreceding() {
        val c = clusterWithSize()
        // cluster at 10 and at 80; seek from 95 → nearest preceding is 80
        val data = ByteArray(200) { 0x33 }
        System.arraycopy(c, 0, data, 10, c.size)
        System.arraycopy(c, 0, data, 80, c.size)
        val found = MatroskaClusterFinder.findNearestClusterStart(
            data = data,
            position = 95L,
            tip = data.size.toLong(),
        )
        assertEquals(80L, found)
    }

    @Test
    fun findNearestClusterStart_respectsMaxScanBack() {
        val c = clusterWithSize()
        val data = ByteArray(200) { 0x33 }
        System.arraycopy(c, 0, data, 10, c.size)
        // Position far past marker; maxScanBack too small to reach it.
        val found = MatroskaClusterFinder.findNearestClusterStart(
            data = data,
            position = 190L,
            tip = data.size.toLong(),
            maxScanBack = 50L,
        )
        assertEquals(0L, found)
    }

    @Test
    fun findNearestClusterStart_rejectsZeroSizeVarint() {
        // False-positive Cluster ID bytes inside frame data, followed by 0x00 size.
        val data = ByteArray(200) { 0x33 }
        System.arraycopy(MatroskaClusterFinder.CLUSTER_ID, 0, data, 80, 4)
        data[84] = 0x00
        val found = MatroskaClusterFinder.findNearestClusterStart(
            data = data,
            position = 100L,
            tip = data.size.toLong(),
        )
        assertEquals(0L, found)
    }

    @Test
    fun findNearestClusterStart_skipsCandidateNearTipSafetyMargin() {
        // Large tip so margin is tip/8; place only cluster inside the margin zone.
        val tip = 8_000L
        val margin = ApproximateByteSeekMap.safetyMarginFor(tip)
        val data = ByteArray(tip.toInt()) { 0x33 }
        val nearTip = (tip - margin / 2).toInt()
        val c = clusterWithSize()
        System.arraycopy(c, 0, data, nearTip, c.size)
        val found = MatroskaClusterFinder.findNearestClusterStart(
            data = data,
            position = tip - 10,
            tip = tip,
        )
        assertEquals(0L, found)
    }

    @Test
    fun isValidEbmlSizeVarint_rejectsZero() {
        assertFalse(MatroskaClusterFinder.isValidEbmlSizeVarint(byteArrayOf(0x00), 0))
        assertTrue(MatroskaClusterFinder.isValidEbmlSizeVarint(byteArrayOf(0x81.toByte()), 0))
    }

    @Test
    fun findNearestClusterStart_fileBacked_matchesBuffer() {
        val c = clusterWithSize()
        val data = ByteArray(64) { 0x33 }
        System.arraycopy(c, 0, data, 20, c.size)
        val file = File.createTempFile("cluster", ".mkv")
        try {
            file.writeBytes(data)
            val found = MatroskaClusterFinder.findNearestClusterStart(
                file = file,
                position = 40L,
                tip = data.size.toLong(),
            )
            assertEquals(20L, found)

            RandomAccessFile(file, "r").use { raf ->
                val fromRaf = MatroskaClusterFinder.findNearestClusterStart(
                    raf = raf,
                    position = 40L,
                    tip = data.size.toLong(),
                )
                assertEquals(20L, fromRaf)
            }
        } finally {
            file.delete()
        }
    }
}
