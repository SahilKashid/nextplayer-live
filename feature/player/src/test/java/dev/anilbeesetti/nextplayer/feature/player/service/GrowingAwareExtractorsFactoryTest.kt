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

    @Test
    fun isLikelyGrowing_finishedContentUriIsFalse() {
        val ctx = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("finished-content-extractors", ".mkv")
        try {
            val size = 2L * 1024L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(ByteArray(size.toInt()) { 0x5A })
            }
            file.setLastModified(System.currentTimeMillis() - 60_000L)
            val uri = registerFileContentProvider(ctx, file, "finished.mkv")
            assertFalse(
                "finished Open-with content:// must keep cue-seek enabled",
                GrowingAwareExtractorsFactory.isLikelyGrowing(ctx, uri),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun isLikelyGrowing_partialNameContentUriIsTrue() {
        val ctx = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("partial-content-extractors", ".mkv")
        try {
            val size = 2L * 1024L * 1024L
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(ByteArray(size.toInt()) { 0x5A })
            }
            file.setLastModified(System.currentTimeMillis() - 60_000L)
            val uri = registerFileContentProvider(ctx, file, "movie.mkv.part")
            assertTrue(GrowingAwareExtractorsFactory.isLikelyGrowing(ctx, uri))
        } finally {
            file.delete()
        }
    }

    private fun registerFileContentProvider(
        context: android.content.Context,
        file: File,
        displayName: String,
    ): Uri {
        val authority = "dev.anilbeesetti.nextplayer.test.extractors." + java.util.UUID.randomUUID()
        val provider = object : android.content.ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?,
            ): android.database.Cursor {
                val cols = projection ?: arrayOf(
                    android.provider.OpenableColumns.DISPLAY_NAME,
                    android.provider.OpenableColumns.SIZE,
                )
                val matrix = android.database.MatrixCursor(cols)
                val row = Array<Any?>(cols.size) { null }
                cols.forEachIndexed { i, col ->
                    row[i] = when (col) {
                        android.provider.OpenableColumns.DISPLAY_NAME -> displayName
                        android.provider.OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                }
                matrix.addRow(row)
                return matrix
            }
            override fun getType(uri: Uri): String = "video/x-matroska"
            override fun insert(uri: Uri, values: android.content.ContentValues?) = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(
                uri: Uri,
                values: android.content.ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?,
            ) = 0
            override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor {
                val pfd = android.os.ParcelFileDescriptor.open(
                    file,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
                return android.content.res.AssetFileDescriptor(pfd, 0, file.length())
            }
        }
        val info = android.content.pm.ProviderInfo().apply {
            this.authority = authority
            this.exported = true
        }
        provider.attachInfo(context, info)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
        return Uri.parse("content://$authority/video")
    }
}
