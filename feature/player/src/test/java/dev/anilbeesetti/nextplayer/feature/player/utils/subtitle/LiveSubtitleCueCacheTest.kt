package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class LiveSubtitleCueCacheTest {

    @Test
    fun putAndGet_roundTripsCuesThroughDiskAndMemory() {
        val context = RuntimeEnvironment.getApplication()
        val format = Format.Builder()
            .setId("1")
            .setLanguage("en")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
        val key = LiveSubtitleCueCache.buildKey(
            mediaId = "media-1",
            mediaUri = Uri.parse("file:///tmp/movie.mkv"),
            externalUri = null,
            format = format,
            textTrackIndex = 0,
            context = context,
        )
        val cues = listOf(
            TimedCue(0L, 1000L, "Hello"),
            TimedCue(1000L, 2000L, "World"),
        )
        LiveSubtitleCueCache.put(context, key, cues)
        assertEquals(cues, LiveSubtitleCueCache.getMemoryOnly(key))
        assertEquals(cues, LiveSubtitleCueCache.get(context, key))
        val cacheFiles = context.cacheDir.resolve("subtitles").list()?.toList().orEmpty()
        assertTrue(cacheFiles.any { it.startsWith("live_cues_") && it.endsWith(".bin") })
    }
}
