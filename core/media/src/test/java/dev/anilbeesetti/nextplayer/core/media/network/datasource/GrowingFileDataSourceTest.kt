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



    @Test
    fun zeroPaddedReadableEnd_findsTipBeforeZeroTail() {
        val file = File.createTempFile("growing-zeropad", ".bin")
        try {
            val declared = 4L * 1024L * 1024L // 4 MiB zero-preallocated
            val prefix = 384L * 1024L // 384 KiB real data
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(declared)
                raf.seek(0)
                raf.write(ByteArray(prefix.toInt()) { (it % 250 + 1).toByte() })
                // Ensure a stretch of explicit zeros after the prefix (setLength already zero-fills
                // on most platforms; write zeros to be explicit for the test).
                raf.seek(prefix)
                raf.write(ByteArray((declared - prefix).toInt()) { 0 })
            }
            assertEquals(declared, file.length())
            RandomAccessFile(file, "r").use { raf ->
                assertTrue(SparseAwareFileLength.quickTailIsAllZeros(raf, declared))
                val tip = SparseAwareFileLength.zeroPaddedReadableEnd(raf, declared)
                assertTrue(
                    "tip=$tip should be near prefix=$prefix",
                    tip in (prefix - 64) .. prefix,
                )
                assertTrue(SparseAwareFileLength.isSparsePartial(declared, tip))
            }
            val viaReadable = SparseAwareFileLength.readableEnd(
                file.absolutePath,
                declared,
                fd = null,
                preferZeroTailScan = true,
            )
            assertTrue(
                "readableEnd=$viaReadable should be near prefix=$prefix",
                viaReadable in (prefix - 64) .. prefix,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun zeroPaddedReadableEnd_allDataReturnsDeclared() {
        val file = File.createTempFile("growing-full", ".bin")
        try {
            val size = 128L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(ByteArray(size.toInt()) { 0x5A })
            }
            RandomAccessFile(file, "r").use { raf ->
                val tip = SparseAwareFileLength.zeroPaddedReadableEnd(raf, size)
                assertEquals(size, tip)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun extendZeroPaddedTip_advancesWhenDataWrittenPastTip() {
        val file = File.createTempFile("growing-extend", ".bin")
        try {
            val declared = 2L * 1024L * 1024L
            val prefix1 = 100L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(declared)
                raf.seek(0)
                raf.write(ByteArray(prefix1.toInt()) { 1 })
            }
            RandomAccessFile(file, "r").use { raf ->
                val tip1 = SparseAwareFileLength.zeroPaddedReadableEnd(raf, declared)
                assertTrue(tip1 in (prefix1 - 64)..prefix1)
            }
            val prefix2 = 300L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(prefix1)
                raf.write(ByteArray((prefix2 - prefix1).toInt()) { 2 })
            }
            RandomAccessFile(file, "r").use { raf ->
                val tip2 = SparseAwareFileLength.extendZeroPaddedTip(raf, prefix1, declared)
                assertTrue("extended tip=$tip2", tip2 in (prefix2 - 64)..prefix2)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun incompleteLocalMedia_partialSuffixesAreUniversal() {
        assertTrue(IncompleteLocalMedia.looksPartialFileName("movie.mp4.part"))
        assertTrue(IncompleteLocalMedia.looksPartialFileName("movie.mp4.crdownload"))
        assertTrue(IncompleteLocalMedia.looksPartialFileName("movie.mp4.!ut"))
        assertTrue(IncompleteLocalMedia.looksPartialFileName("movie.tmp"))
        assertTrue(IncompleteLocalMedia.looksPartialFileName("movie.download"))
        assertTrue(IncompleteLocalMedia.looksPartialFileName("movie.aria2"))
        assertTrue(IncompleteLocalMedia.looksPartialFileName("movie.bc!"))
        assertFalse(IncompleteLocalMedia.looksPartialFileName("movie.mp4"))
        assertFalse(IncompleteLocalMedia.looksPartialFileName("show.mkv"))
    }

    @Test
    fun incompleteLocalMedia_zeroTailIsIncompleteOnAnyPath() {
        val file = File.createTempFile("movies-folder-style", ".mkv")
        try {
            val declared = 4L * 1024L * 1024L
            val prefix = 384L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(declared)
                raf.seek(0)
                raf.write(ByteArray(prefix.toInt()) { (it % 250 + 1).toByte() })
                raf.seek(prefix)
                raf.write(ByteArray((declared - prefix).toInt()) { 0 })
            }
            val snapshot = IncompleteLocalMedia.inspect(file.absolutePath)
            assertTrue("zero-preallocated file must be incomplete anywhere", snapshot.incomplete)
            assertTrue(snapshot.tipBehindDeclared)
            assertFalse(snapshot.partialName)
            assertTrue(snapshot.readableEnd < snapshot.declaredLength)
        } finally {
            file.delete()
        }
    }

    @Test
    fun incompleteLocalMedia_finishedNonZeroTailIsCompleteAnywhere() {
        val file = File.createTempFile("finished-library", ".mkv")
        try {
            val size = 2L * 1024L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                // Non-zero throughout, including the last 256KiB (cues / real media).
                raf.write(ByteArray(size.toInt()) { 0x5A })
            }
            val snapshot = IncompleteLocalMedia.inspect(file.absolutePath)
            assertFalse("finished file with real tail must not be incomplete", snapshot.incomplete)
            assertFalse(snapshot.tipBehindDeclared)
            assertEquals(size, snapshot.declaredLength)
            assertEquals(size, snapshot.readableEnd)
        } finally {
            file.delete()
        }
    }

    @Test
    fun incompleteLocalMedia_doesNotKeyOffDirectoryNames() {
        // A complete file whose path *looks* like a download-manager folder is still complete.
        val complete = File.createTempFile("finished-in-download-named-dir", ".mkv")
        try {
            val size = 2L * 1024L * 1024L
            RandomAccessFile(complete, "rw").use { raf ->
                raf.write(ByteArray(size.toInt()) { 0x7E })
            }
            assertFalse(IncompleteLocalMedia.isIncomplete(complete))
            // Folder tokens in a path string must not flip the result by themselves.
            assertFalse(
                IncompleteLocalMedia.isIncomplete(
                    "/storage/emulated/0/Download/1DM/Videos/show.mkv",
                    "show.mkv",
                ),
            )
            assertFalse(
                IncompleteLocalMedia.isIncomplete(
                    "/storage/emulated/0/Movies/admin-cut.mkv",
                    "admin-cut.mkv",
                ),
            )
            assertFalse(IncompleteLocalMedia.looksPartialFileName("The Gentlemen 2024 S02E02.mkv"))
            assertFalse(
                IncompleteLocalMedia.isIncomplete(
                    path = null,
                    fileName = "The Gentlemen 2024 S02E02.mkv",
                ),
            )
        } finally {
            complete.delete()
        }
    }

    @Test
    fun incompleteLocalMedia_partialNameAloneIsIncomplete() {
        assertTrue(IncompleteLocalMedia.isIncomplete(path = null, fileName = "show.mkv.part"))
        assertTrue(IncompleteLocalMedia.isIncomplete("/tmp/show.mkv.crdownload"))
    }
}
