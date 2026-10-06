package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class LiveSubtitleCueCacheTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        LiveSubtitleCueCache.clearMemory()
        LiveSubtitleCueCache.resetStats()
        // Wipe leftover live_cues files between tests.
        context.cacheDir.resolve("subtitles").listFiles()?.forEach { it.delete() }
    }

    private fun sampleFormat(id: String = "1"): Format =
        Format.Builder()
            .setId(id)
            .setLanguage("en")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()

    private fun keyFor(
        mediaId: String = "media-1",
        mediaUri: Uri = Uri.parse("file:///tmp/movie.mkv"),
        format: Format = sampleFormat(),
        textTrackIndex: Int = 0,
    ): String = LiveSubtitleCueCache.buildKey(
        mediaId = mediaId,
        mediaUri = mediaUri,
        externalUri = null,
        format = format,
        textTrackIndex = textTrackIndex,
        context = context,
    )

    @Test
    fun putAndGet_memoryHit() {
        val key = keyFor()
        val cues = listOf(
            TimedCue(0L, 1000L, "Hello"),
            TimedCue(1000L, 2000L, "World"),
        )
        LiveSubtitleCueCache.put(context, key, cues)
        assertEquals(cues, LiveSubtitleCueCache.getMemoryOnly(key))
        assertEquals(cues, LiveSubtitleCueCache.get(context, key))
    }

    @Test
    fun put_clearMemory_get_hitsDisk() {
        val key = keyFor(mediaId = "disk-reopen")
        val cues = listOf(TimedCue(0L, 500L, "Cached"))
        LiveSubtitleCueCache.put(context, key, cues)
        assertNotNull(LiveSubtitleCueCache.getMemoryOnly(key))

        LiveSubtitleCueCache.clearMemory()
        assertNull(LiveSubtitleCueCache.getMemoryOnly(key))

        val fromDisk = LiveSubtitleCueCache.get(context, key)
        assertEquals(cues, fromDisk)
        // Disk hit should repopulate memory.
        assertEquals(cues, LiveSubtitleCueCache.getMemoryOnly(key))
    }

    @Test
    fun put_skipsEmptyCueLists() {
        val key = keyFor(mediaId = "media-empty")
        LiveSubtitleCueCache.put(context, key, emptyList())
        assertNull(LiveSubtitleCueCache.getMemoryOnly(key))
        assertNull(LiveSubtitleCueCache.get(context, key))
    }

    @Test
    fun buildKey_stableWhenMtimeMissing_usesSizeOnly() {
        // file:// with a real temp file → size included; mtime must not be in the key.
        val tmp = File.createTempFile("live-cue-key", ".mkv")
        tmp.writeBytes(ByteArray(128) { 1 })
        try {
            val uri = Uri.fromFile(tmp)
            val key1 = keyFor(mediaId = "stable", mediaUri = uri)
            // Touch mtime — key must not change (mtime is not part of the key).
            tmp.setLastModified(tmp.lastModified() + 60_000L)
            val key2 = keyFor(mediaId = "stable", mediaUri = uri)
            assertEquals(key1, key2)
            assertTrue(key1.contains(tmp.length().toString()))
            // No second numeric meta field for mtime after size.
            val parts = key1.split("|")
            assertEquals(tmp.length().toString(), parts.last())
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun buildKey_normalizesUriSchemeCasing() {
        val a = LiveSubtitleCueCache.normalizeUriString(Uri.parse("FILE:///tmp/Movie.MKV"))
        val b = LiveSubtitleCueCache.normalizeUriString(Uri.parse("file:///tmp/Movie.MKV"))
        assertEquals(b, a)
        assertTrue(a.startsWith("file:"))
    }

    @Test
    fun corruptDiskFile_isDeletedAndIgnored() {
        val key = keyFor(mediaId = "corrupt")
        val cues = listOf(TimedCue(0L, 100L, "Good"))
        LiveSubtitleCueCache.put(context, key, cues)
        LiveSubtitleCueCache.clearMemory()

        val file = LiveSubtitleCueCache.cacheFileForKey(context, key)
        assertTrue(file.exists())
        // Overwrite with garbage.
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }

        assertNull(LiveSubtitleCueCache.get(context, key))
        assertFalse("corrupt file should be deleted", file.exists())
    }

    @Test
    fun sameMediaAndTrack_hitsAfterSimulatedReopen() {
        val key = keyFor(mediaId = "reopen-media", textTrackIndex = 1)
        val cues = listOf(
            TimedCue(10L, 20L, "One"),
            TimedCue(20L, 30L, "Two"),
        )
        LiveSubtitleCueCache.put(context, key, cues)

        // Simulate process/session boundary: memory gone, disk remains.
        LiveSubtitleCueCache.clearMemory()
        val reopenedKey = keyFor(mediaId = "reopen-media", textTrackIndex = 1)
        assertEquals(key, reopenedKey)
        assertEquals(cues, LiveSubtitleCueCache.get(context, reopenedKey))
    }

    @Test
    fun putAndGet_writesBinaryCacheFile() {
        val key = keyFor(mediaId = "bin-check")
        LiveSubtitleCueCache.put(context, key, listOf(TimedCue(0L, 1L, "x")))
        val cacheFiles = context.cacheDir.resolve("subtitles").list()?.toList().orEmpty()
        assertTrue(cacheFiles.any { it.startsWith("live_cues_") && it.endsWith(".bin") })
    }

    @Test
    fun differentTracks_differentKeys() {
        val a = keyFor(textTrackIndex = 0)
        val b = keyFor(textTrackIndex = 1)
        assertNotEquals(a, b)
    }
}
