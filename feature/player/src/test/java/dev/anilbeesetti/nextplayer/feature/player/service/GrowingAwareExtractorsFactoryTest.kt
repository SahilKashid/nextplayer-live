package dev.anilbeesetti.nextplayer.feature.player.service

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class GrowingAwareExtractorsFactoryTest {

    @Test
    fun isLikelyGrowing_finishedStableFileIsFalse() {
        val ctx = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("finished-extractors", ".mkv")
        try {
            val size = 2L * 1024L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(ByteArray(size.toInt()) { 0x5A })
            }
            file.setLastModified(System.currentTimeMillis() - 60_000L)
            val uri = Uri.fromFile(file)
            assertFalse(GrowingAwareExtractorsFactory.isLikelyGrowing(ctx, uri))
        } finally {
            file.delete()
        }
    }

    @Test
    fun isLikelyGrowing_zeroPreallocatedFileIsTrue() {
        val ctx = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("growing-extractors", ".mkv")
        try {
            val declared = 4L * 1024L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(declared)
                raf.seek(0)
                raf.write(ByteArray(384 * 1024) { 1 })
                raf.seek(384L * 1024L)
                raf.write(ByteArray((declared - 384L * 1024L).toInt()) { 0 })
            }
            file.setLastModified(System.currentTimeMillis() - 60_000L)
            val uri = Uri.fromFile(file)
            assertTrue(GrowingAwareExtractorsFactory.isLikelyGrowing(ctx, uri))
        } finally {
            file.delete()
        }
    }

    @Test
    fun isLikelyGrowing_networkUriIsFalse() {
        val ctx = RuntimeEnvironment.getApplication()
        assertFalse(
            GrowingAwareExtractorsFactory.isLikelyGrowing(
                ctx,
                Uri.parse("https://example.com/show.mkv"),
            ),
        )
    }
}
