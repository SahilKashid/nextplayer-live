package dev.anilbeesetti.nextplayer.core.media.network.datasource

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class GrowingFileDataSourceTest {

    @Test
    fun open_returnsLengthUnset() {
        val file = File.createTempFile("growing", ".bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val source = GrowingFileDataSource()
        try {
            val length = source.open(DataSpec(file.toUri()))
            assertEquals(C.LENGTH_UNSET.toLong(), length)
        } finally {
            source.close()
            file.delete()
        }
    }

    @Test
    fun read_seesBytesAppendedWhileOpen() {
        val file = File.createTempFile("growing", ".bin")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        val started = CountDownLatch(1)
        val appended = AtomicBoolean(false)
        val appender = Thread {
            started.await(5, TimeUnit.SECONDS)
            Thread.sleep(100)
            FileOutputStream(file, /* append = */ true).use { out ->
                out.write(byteArrayOf(5, 6, 7, 8))
            }
            appended.set(true)
        }
        appender.start()

        val source = GrowingFileDataSource()
        try {
            assertEquals(C.LENGTH_UNSET.toLong(), source.open(DataSpec(file.toUri())))

            val buffer = ByteArray(8)
            var total = 0
            started.countDown()
            while (total < 8) {
                val n = source.read(buffer, total, 8 - total)
                assertTrue("expected more data, got $n (appended=${appended.get()})", n > 0)
                total += n
            }
            assertTrue(appended.get())
            assertEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).toList(), buffer.toList())
        } finally {
            source.close()
            appender.join(5000)
            file.delete()
        }
    }

    @Test
    fun resolvePath_supportsFileUriAndRawPath() {
        assertEquals("/tmp/video.mkv", GrowingFileDataSource.resolvePath("file:///tmp/video.mkv".toUri()))
        assertEquals("/tmp/video.mkv", GrowingFileDataSource.resolvePath("/tmp/video.mkv".toUri()))
    }

    @Test
    fun looksPartialFileName_detectsDownloaderSuffixes() {
        assertTrue(GrowingFileDataSource.looksPartialFileName("movie.mp4.part"))
        assertTrue(GrowingFileDataSource.looksPartialFileName("movie.mp4.crdownload"))
        assertTrue(GrowingFileDataSource.looksPartialFileName("movie.mp4.!ut"))
        assertTrue(GrowingFileDataSource.looksPartialFileName("movie.tmp"))
        assertTrue(GrowingFileDataSource.looksPartialFileName("movie.download"))
        assertTrue(GrowingFileDataSource.looksPartialFileName("movie.aria2"))
        assertTrue(GrowingFileDataSource.looksPartialFileName("movie.bc!"))
        assertFalse(GrowingFileDataSource.looksPartialFileName("movie.mp4"))
    }

    @Test
    fun open_succeedsForTinyFileWithLengthUnset() {
        val file = File.createTempFile("growing-tiny", ".bin")
        file.writeBytes(byteArrayOf(1))
        val source = GrowingFileDataSource()
        try {
            assertEquals(C.LENGTH_UNSET.toLong(), source.open(DataSpec(file.toUri())))
        } finally {
            source.close()
            file.delete()
        }
    }

    @Test
    fun chooseReadableEnd_usesHoleWhenInsideDeclared() {
        assertEquals(2_097_152L, SparseAwareFileLength.chooseReadableEnd(31_762_747L, 2_097_152L))
        assertEquals(31_762_747L, SparseAwareFileLength.chooseReadableEnd(31_762_747L, 31_762_747L))
        assertEquals(31_762_747L, SparseAwareFileLength.chooseReadableEnd(31_762_747L, 0L))
        assertEquals(31_762_747L, SparseAwareFileLength.chooseReadableEnd(31_762_747L, -1L))
        assertEquals(0L, SparseAwareFileLength.chooseReadableEnd(0L, 0L))
    }

    @Test
    fun isSparsePartial_whenReadableBehindDeclared() {
        assertTrue(SparseAwareFileLength.isSparsePartial(31_762_747L, 2_097_152L))
        assertFalse(SparseAwareFileLength.isSparsePartial(31_762_747L, 31_762_747L))
        assertFalse(SparseAwareFileLength.isSparsePartial(0L, 0L))
    }

    @Test
    fun createSparseFile_documentsAdmPreallocationShape() {
        // Reproduce ADM preallocation: truncate to final size, write only a prefix.
        val file = File.createTempFile("growing-sparse", ".bin")
        try {
            val declared = 8L * 1024L * 1024L
            val prefix = 256L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(declared)
                raf.seek(0)
                raf.write(ByteArray(prefix.toInt()) { 0xAB.toByte() })
            }
            assertEquals(declared, file.length())
            // Pure heuristic: hole tip would be prefix if SEEK_HOLE works.
            assertEquals(prefix, SparseAwareFileLength.chooseReadableEnd(declared, prefix))
            assertTrue(SparseAwareFileLength.isSparsePartial(declared, prefix))
            // readableEnd without Android Os support still returns declared (safe fallback);
            // on Robolectric API 26+ it may or may not implement SEEK_HOLE — either is OK as
            // long as chooseReadableEnd / isSparsePartial stay correct.
            val tip = SparseAwareFileLength.readableEnd(file.absolutePath, declared, fd = null)
            assertTrue(
                "readableEnd should be tip or declared, got $tip",
                tip == prefix || tip == declared,
            )
        } finally {
            file.delete()
        }
    }
}
