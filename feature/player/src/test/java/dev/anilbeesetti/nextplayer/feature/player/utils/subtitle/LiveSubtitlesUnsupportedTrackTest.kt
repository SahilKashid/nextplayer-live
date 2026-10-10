package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the live-panel contract: [SubtitleCueLoader.LoadResult.isUnsupportedBitmapTrack]
 * (and therefore [dev.anilbeesetti.nextplayer.feature.player.state.LiveSubtitlesState.isUnsupportedTrack])
 * is true only for image-based formats — never merely because cue extraction returned empty.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class LiveSubtitlesUnsupportedTrackTest {

    @Test
    fun emptyNonBitmap_isNotUnsupportedTrack() {
        val srt = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
        val ass = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_SSA)
            .build()
        val emptyResultSrt = SubtitleCueLoader.LoadResult(
            cues = emptyList(),
            cacheKey = "k-srt",
            fromCache = false,
            isUnsupportedBitmapTrack = EmbeddedSubtitleCueExtractor.isBitmapSubtitle(srt),
        )
        val emptyResultAss = SubtitleCueLoader.LoadResult(
            cues = emptyList(),
            cacheKey = "k-ass",
            fromCache = false,
            isUnsupportedBitmapTrack = EmbeddedSubtitleCueExtractor.isBitmapSubtitle(ass),
        )
        assertFalse(emptyResultSrt.isUnsupportedBitmapTrack)
        assertFalse(emptyResultAss.isUnsupportedBitmapTrack)
        assertTrue(emptyResultSrt.cues.isEmpty())
        assertTrue(emptyResultAss.cues.isEmpty())
    }

    @Test
    fun bitmapFormat_isUnsupportedTrackEvenWhenEmpty() {
        val pgs = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build()
        val vob = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_VOBSUB).build()
        val dvb = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_DVBSUBS).build()
        val transcodedPgs = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_PGS)
            .build()

        for (format in listOf(pgs, vob, dvb, transcodedPgs)) {
            val result = SubtitleCueLoader.LoadResult(
                cues = emptyList(),
                cacheKey = "k-bitmap",
                fromCache = false,
                isUnsupportedBitmapTrack = EmbeddedSubtitleCueExtractor.isBitmapSubtitle(format),
            )
            assertTrue(
                "expected unsupported for ${format.sampleMimeType}/${format.codecs}",
                result.isUnsupportedBitmapTrack,
            )
        }
    }
}
