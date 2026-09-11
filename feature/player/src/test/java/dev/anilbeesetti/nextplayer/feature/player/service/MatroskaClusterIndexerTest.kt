package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.util.UnstableApi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MatroskaClusterIndexerTest {

    @Test
    fun indexBytes_acceptsSyntheticClustersWithTimecode() {
        val data = syntheticMkv(
            clusters = listOf(
                cluster(timecode = 0L, extra = 32),
                cluster(timecode = 1_000L, extra = 32),
                cluster(timecode = 2_000L, extra = 32),
            ),
        )
        val index = MatroskaClusterIndexer.indexBytes(data)
        assertNotNull(index)
        assertEquals(3, index!!.size)
        for (i in 1 until index.size) {
            assertTrue(index.positions[i] > index.positions[i - 1])
            assertTrue(index.timesUs[i] > index.timesUs[i - 1])
        }
        // default scale 1_000_000 → timeUs = timecode * 1000
        assertEquals(0L, index.timesUs[0])
        assertEquals(1_000_000L, index.timesUs[1])
        assertEquals(2_000_000L, index.timesUs[2])

        val seekMap = MatroskaClusterIndexer.toSeekMap(index, durationUs = 10_000_000L)
        assertNotNull(seekMap)
        assertTrue(seekMap!!.isSeekable)
        assertEquals(10_000_000L, seekMap.durationUs)
        val mid = seekMap.getSeekPoints(1_000_000L)
        assertEquals(index.positions[1], mid.first.position)
        assertEquals(1_000_000L, mid.first.timeUs)
    }

    @Test
    fun indexBytes_usesInfoTimecodeScale() {
        val data = syntheticMkv(
            scale = 1_000_000_000L, // 1 second per tick
            clusters = listOf(
                cluster(timecode = 0L, extra = 16),
                cluster(timecode = 2L, extra = 16),
            ),
        )
        val index = MatroskaClusterIndexer.indexBytes(data)
        assertNotNull(index)
        assertEquals(1_000_000_000L, index!!.timecodeScale)
        assertEquals(0L, index.timesUs[0])
        assertEquals(2_000_000L, index.timesUs[1])
    }

    @Test
    fun indexBytes_rejectsGarbageClusterIdWithBadSize() {
        val garbage = ByteArray(256) { 0x33 }
        // False-positive Cluster ID + invalid size byte 0x00 (no length mask).
        System.arraycopy(CLUSTER_ID, 0, garbage, 40, CLUSTER_ID.size)
        garbage[44] = 0x00
        // Another false positive with a huge / implausible first size byte that is not a valid
        // walk from an EBML header.
        System.arraycopy(CLUSTER_ID, 0, garbage, 80, CLUSTER_ID.size)
        garbage[84] = 0x01
        assertNull(MatroskaClusterIndexer.indexBytes(garbage, tip = garbage.size.toLong(), safeTip = garbage.size.toLong()))
    }

    @Test
    fun indexBytes_rejectsClusterIdWithoutTimecode() {
        val fakeCluster = CLUSTER_ID + encodeSize(8) + ByteArray(8) { 0x11 }
        val data = syntheticContainer(level1 = fakeCluster + fakeCluster)
        assertNull(MatroskaClusterIndexer.indexBytes(data, tip = data.size.toLong(), safeTip = data.size.toLong()))
    }

    @Test
    fun indexBytes_needsAtLeastTwoClusters() {
        val data = syntheticMkv(clusters = listOf(cluster(timecode = 0L, extra = 16)))
        assertNull(MatroskaClusterIndexer.indexBytes(data, tip = data.size.toLong(), safeTip = data.size.toLong()))
    }

    @Test
    fun indexBytes_skipsClusterInsideSafetyMargin() {
        val clusters = listOf(
            cluster(timecode = 0L, extra = 16),
            cluster(timecode = 1_000L, extra = 16),
        )
        val core = syntheticMkv(clusters = clusters)
        // Pad so the file is large enough that safety margin swallows a trailing cluster
        // if we placed one near the end. Two early clusters stay accepted.
        val padded = core + ByteArray(8_000) { 0x00 }
        val index = MatroskaClusterIndexer.indexBytes(
            data = padded,
            tip = padded.size.toLong(),
        )
        assertNotNull(index)
        assertEquals(2, index!!.size)
        val safe = MatroskaClusterIndexer.safeTip(padded.size.toLong())
        assertTrue(index.positions.last() < safe)
    }

    @Test
    fun nearestAtOrBefore_picksPreceding() {
        val positions = longArrayOf(100L, 400L, 900L)
        assertEquals(100L, MatroskaClusterIndexer.nearestAtOrBefore(positions, 50L))
        assertEquals(100L, MatroskaClusterIndexer.nearestAtOrBefore(positions, 100L))
        assertEquals(400L, MatroskaClusterIndexer.nearestAtOrBefore(positions, 700L))
        assertEquals(900L, MatroskaClusterIndexer.nearestAtOrBefore(positions, 2_000L))
    }

    @Test
    fun indexFile_matchesIndexBytes() {
        val data = syntheticMkv(
            clusters = listOf(
                cluster(timecode = 0L, extra = 24),
                cluster(timecode = 500L, extra = 24),
            ),
        )
        val file = File.createTempFile("cluster-index", ".mkv")
        try {
            file.writeBytes(data)
            val fromFile = MatroskaClusterIndexer.indexFile(file, tip = data.size.toLong())
            val fromBytes = MatroskaClusterIndexer.indexBytes(data)
            assertNotNull(fromFile)
            assertNotNull(fromBytes)
            assertEquals(fromBytes!!.size, fromFile!!.size)
            assertTrue(fromFile.positions.contentEquals(fromBytes.positions))
            assertTrue(fromFile.timesUs.contentEquals(fromBytes.timesUs))
        } finally {
            file.delete()
        }
    }

    @Test
    fun safeTip_appliesOneMiBOrTipOverEight() {
        assertEquals(0L, MatroskaClusterIndexer.safeTip(0L))
        assertEquals(7_000_000L, MatroskaClusterIndexer.safeTip(8_000_000L))
        assertEquals(9L * 1024L * 1024L, MatroskaClusterIndexer.safeTip(10L * 1024L * 1024L))
    }

    companion object {
        private val EBML_ID = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        private val SEGMENT_ID = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)
        private val INFO_ID = byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66)
        private val CLUSTER_ID = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        private val TIMECODE_SCALE_ID = byteArrayOf(0x2A, 0xD7.toByte(), 0xB1.toByte())
        private val TIMECODE_ID = byteArrayOf(0xE7.toByte())
        private val UNKNOWN_SIZE_8 = byteArrayOf(
            0x01,
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
        )

        private fun encodeSize(value: Long): ByteArray {
            require(value >= 0L)
            for (length in 1..8) {
                val max = (1L shl (7 * length)) - 2L
                if (value <= max) {
                    val encoded = value or (1L shl (7 * length))
                    return ByteArray(length) { i ->
                        ((encoded shr (8 * (length - 1 - i))) and 0xFFL).toByte()
                    }
                }
            }
            error("size too large")
        }

        private fun encodeUint(value: Long): ByteArray {
            if (value == 0L) return byteArrayOf(0)
            var remaining = value
            val bytes = ArrayList<Byte>()
            while (remaining > 0L) {
                bytes.add(0, (remaining and 0xFFL).toByte())
                remaining = remaining ushr 8
            }
            return bytes.toByteArray()
        }

        private fun element(id: ByteArray, payload: ByteArray): ByteArray =
            id + encodeSize(payload.size.toLong()) + payload

        private fun cluster(timecode: Long, extra: Int): ByteArray {
            val tcBytes = encodeUint(timecode)
            val tcElem = element(TIMECODE_ID, tcBytes)
            val payload = tcElem + ByteArray(extra.coerceAtLeast(0)) { 0x5A }
            return element(CLUSTER_ID, payload)
        }

        private fun info(scale: Long): ByteArray {
            val scaleBytes = encodeUint(scale)
            return element(INFO_ID, element(TIMECODE_SCALE_ID, scaleBytes))
        }

        private fun syntheticContainer(level1: ByteArray): ByteArray {
            val ebmlPayload = byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01)
            val ebml = element(EBML_ID, ebmlPayload)
            return ebml + SEGMENT_ID + UNKNOWN_SIZE_8 + level1
        }

        private fun syntheticMkv(
            scale: Long = MatroskaClusterIndexer.DEFAULT_TIMECODE_SCALE,
            clusters: List<ByteArray>,
        ): ByteArray {
            var level1 = info(scale)
            for (cluster in clusters) {
                level1 += cluster
            }
            return syntheticContainer(level1)
        }
    }
}
