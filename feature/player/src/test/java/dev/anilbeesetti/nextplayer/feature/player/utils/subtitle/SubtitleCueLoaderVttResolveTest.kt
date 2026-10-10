package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * External WebVTT tracks are often exposed by Media3 as APPLICATION_MEDIA3_CUES with
 * codecs=text/vtt. The live panel must still resolve the sideloaded .vtt URI.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class SubtitleCueLoaderVttResolveTest {

    @Test
    fun resolveExternal_transcodedVtt_singleConfig_byCodecsMime() {
        val vttUri = Uri.parse("file:///sdcard/Movies/movie.vtt")
        val config = MediaItem.SubtitleConfiguration.Builder(vttUri)
            .setId("content://downloads/movie.vtt")
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLabel("movie.vtt")
            .build()
        // Typical after text-track transcoding: id/label may not match config.
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .build()

        val resolved = SubtitleCueLoader.resolveExternalSubtitleUri(format, listOf(config))
        assertEquals(vttUri, resolved)
    }

    @Test
    fun resolveExternal_transcodedVtt_uniqueMimeAmongConfigs() {
        val vttUri = Uri.parse("file:///sdcard/Movies/movie.vtt")
        val srtUri = Uri.parse("file:///sdcard/Movies/movie.srt")
        val configs = listOf(
            MediaItem.SubtitleConfiguration.Builder(srtUri)
                .setId("srt")
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLabel("movie.srt")
                .build(),
            MediaItem.SubtitleConfiguration.Builder(vttUri)
                .setId("vtt")
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLabel("movie.vtt")
                .build(),
        )
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .build()

        val resolved = SubtitleCueLoader.resolveExternalSubtitleUri(format, configs)
        assertEquals(vttUri, resolved)
    }

    @Test
    fun resolveExternal_embeddedAss_notConfusedWithLoneExternalVtt() {
        val vttUri = Uri.parse("file:///sdcard/Movies/movie.vtt")
        val config = MediaItem.SubtitleConfiguration.Builder(vttUri)
            .setId("vtt")
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLabel("movie.vtt")
            .build()
        val embeddedAss = Format.Builder()
            .setId("1")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_SSA)
            .setLanguage("eng")
            .build()

        val resolved = SubtitleCueLoader.resolveExternalSubtitleUri(embeddedAss, listOf(config))
        assertNull(resolved)
    }

    @Test
    fun resolveExternal_byConfigId() {
        val vttUri = Uri.parse("file:///cache/movie.vtt")
        val originalId = "content://media/external/file/42"
        val config = MediaItem.SubtitleConfiguration.Builder(vttUri)
            .setId(originalId)
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLabel("movie.vtt")
            .build()
        val format = Format.Builder()
            .setId(originalId)
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLabel("movie.vtt")
            .build()

        assertEquals(vttUri, SubtitleCueLoader.resolveExternalSubtitleUri(format, listOf(config)))
    }

    @Test
    fun vttFormat_isNotUnsupportedBitmapTrack() {
        val vtt = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()
        val transcoded = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .build()
        assertFalse(EmbeddedSubtitleCueExtractor.isBitmapSubtitle(vtt))
        assertFalse(EmbeddedSubtitleCueExtractor.isBitmapSubtitle(transcoded))
        val result = SubtitleCueLoader.LoadResult(
            cues = emptyList(),
            cacheKey = "k-vtt",
            fromCache = false,
            isUnsupportedBitmapTrack = EmbeddedSubtitleCueExtractor.isBitmapSubtitle(transcoded),
        )
        assertFalse(result.isUnsupportedBitmapTrack)
        assertNotNull(result)
    }
}
