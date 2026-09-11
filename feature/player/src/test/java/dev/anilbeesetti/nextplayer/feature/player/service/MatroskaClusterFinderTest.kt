package dev.anilbeesetti.nextplayer.feature.player.service

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Test

class MatroskaClusterFinderTest {

    @Test
    fun findNearestClusterStart_inSyntheticBuffer_findsMarker() {
        val prefix = ByteArray(100) { 0x11 }
        val cluster = MatroskaClusterFinder.CLUSTER_ID
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
        val c = MatroskaClusterFinder.CLUSTER_ID
        // cluster at 10 and at 80; seek from 90 → nearest preceding is 80
        val data = ByteArray(120) { 0 }
        System.arraycopy(c, 0, data, 10, 4)
        System.arraycopy(c, 0, data, 80, 4)
        val found = MatroskaClusterFinder.findNearestClusterStart(
            data = data,
            position = 95L,
            tip = data.size.toLong(),
        )
        assertEquals(80L, found)
    }

    @Test
    fun findNearestClusterStart_respectsMaxScanBack() {
        val c = MatroskaClusterFinder.CLUSTER_ID
        val data = ByteArray(200) { 0 }
        System.arraycopy(c, 0, data, 10, 4)
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
    fun findNearestClusterStart_fileBacked_matchesBuffer() {
        val c = MatroskaClusterFinder.CLUSTER_ID
        val data = ByteArray(64) { 0x33 }
        System.arraycopy(c, 0, data, 20, 4)
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
