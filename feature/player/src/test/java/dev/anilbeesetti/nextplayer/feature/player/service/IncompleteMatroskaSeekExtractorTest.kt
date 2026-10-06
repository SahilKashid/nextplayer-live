package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class IncompleteMatroskaSeekExtractorTest {

    @Test
    fun replaceSeekMap_emitsIndexSeekMapFromValidatedClusters() {
        val data = syntheticMkv(
            clusters = listOf(
                cluster(timecode = 0L, extra = 24),
                cluster(timecode = 1_000L, extra = 24),
                cluster(timecode = 2_000L, extra = 24),
            ),
        )
        val file = File.createTempFile("incomplete-seek", ".mkv")
        try {
            file.writeBytes(data)
            val tip = data.size.toLong()
            val fake = RecordingExtractor(SeekMap.Unseekable(5_000_000L))
            val wrapper = IncompleteMatroskaSeekExtractor(
                delegate = fake,
                tipProvider = { tip },
                filePathProvider = { file.absolutePath },
            )
            val output = CapturingOutput()
            wrapper.init(output)

            val map = output.seekMap
            assertNotNull(map)
            assertTrue(map is IndexSeekMap)
            assertTrue(map!!.isSeekable)
            assertEquals(5_000_000L, map.durationUs)
            val mid = map.getSeekPoints(1_000_000L)
            assertTrue(mid.first.position > 0L)
            assertEquals(1_000_000L, mid.first.timeUs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun replaceSeekMap_keepsUnseekableWhenFewerThanTwoClusters() {
        val data = syntheticMkv(clusters = listOf(cluster(timecode = 0L, extra = 16)))
        val file = File.createTempFile("incomplete-seek-one", ".mkv")
        try {
            file.writeBytes(data)
            val fake = RecordingExtractor(SeekMap.Unseekable(2_000_000L))
            val wrapper = IncompleteMatroskaSeekExtractor(
                delegate = fake,
                tipProvider = { data.size.toLong() },
                filePathProvider = { file.absolutePath },
            )
            val output = CapturingOutput()
            wrapper.init(output)
            assertNotNull(output.seekMap)
            assertFalse(output.seekMap!!.isSeekable)
            assertEquals(2_000_000L, output.seekMap!!.durationUs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun seek_clampsAndSnapsToIndexedCluster() {
        val data = syntheticMkv(
            clusters = listOf(
                cluster(timecode = 0L, extra = 24),
                cluster(timecode = 1_000L, extra = 24),
                cluster(timecode = 2_000L, extra = 24),
            ),
        )
        val file = File.createTempFile("incomplete-seek-snap", ".mkv")
        try {
            file.writeBytes(data)
            val tip = data.size.toLong()
            val index = MatroskaClusterIndexer.indexFile(file, tip)!!
            val fake = RecordingExtractor(SeekMap.Unseekable(5_000_000L))
            val wrapper = IncompleteMatroskaSeekExtractor(
                delegate = fake,
                tipProvider = { tip },
                filePathProvider = { file.absolutePath },
            )
            wrapper.init(CapturingOutput())

            val pastTip = tip + 50_000L
            wrapper.seek(pastTip, timeUs = 4_000_000L)
            val expected = MatroskaClusterIndexer.nearestAtOrBefore(
                index.positions,
                MatroskaClusterIndexer.safeTip(tip).coerceAtLeast(1L) - 1L,
            )
            assertEquals(expected, fake.lastSeekPosition)
            assertEquals(4_000_000L, fake.lastSeekTimeUs)

            val midTarget = (index.positions[1] + index.positions[2]) / 2L
            wrapper.seek(midTarget, timeUs = 1_500_000L)
            assertEquals(index.positions[1], fake.lastSeekPosition)
        } finally {
            file.delete()
        }
    }

    @Test
    fun replaceSeekMap_passesThroughSeekableMaps() {
        val positions = longArrayOf(0L, 1000L)
        val times = longArrayOf(0L, 1_000_000L)
        val cueMap = IndexSeekMap(positions, times, 2_000_000L)
        val fake = RecordingExtractor(cueMap)
        val wrapper = IncompleteMatroskaSeekExtractor(
            delegate = fake,
            tipProvider = { 10_000L },
            filePathProvider = { null },
        )
        val output = CapturingOutput()
        wrapper.init(output)
        assertTrue(output.seekMap === cueMap)
    }

    private class RecordingExtractor(
        private val initialSeekMap: SeekMap,
    ) : Extractor {
        var lastSeekPosition: Long = -1L
        var lastSeekTimeUs: Long = C.TIME_UNSET

        override fun sniff(input: ExtractorInput): Boolean = true
        override fun getSniffFailureDetails(): List<SniffFailure> = emptyList()
        override fun init(output: ExtractorOutput) {
            output.seekMap(initialSeekMap)
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {
            lastSeekPosition = position
            lastSeekTimeUs = timeUs
        }
        override fun release() = Unit
    }

    private class CapturingOutput : ExtractorOutput {
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int) = error("unused")
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
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

        private fun syntheticMkv(
            scale: Long = MatroskaClusterIndexer.DEFAULT_TIMECODE_SCALE,
            clusters: List<ByteArray>,
        ): ByteArray {
            val ebmlPayload = byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01)
            val ebml = element(EBML_ID, ebmlPayload)
            var level1 = info(scale)
            for (cluster in clusters) {
                level1 += cluster
            }
            return ebml + SEGMENT_ID + UNKNOWN_SIZE_8 + level1
        }
    }
}
