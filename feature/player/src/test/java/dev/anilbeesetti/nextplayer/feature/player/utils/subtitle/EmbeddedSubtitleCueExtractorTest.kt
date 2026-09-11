package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Util
import androidx.media3.extractor.text.CuesWithTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class EmbeddedSubtitleCueExtractorTest {

    @Test
    fun isBitmapSubtitle_detectsPgsFromMimeAndCodecs() {
        val pgs = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build()
        val transcodedPgs = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_PGS)
            .build()
        val srt = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_SUBRIP)
            .build()

        assertTrue(EmbeddedSubtitleCueExtractor.isBitmapSubtitle(pgs))
        assertTrue(EmbeddedSubtitleCueExtractor.isBitmapSubtitle(transcodedPgs))
        assertFalse(EmbeddedSubtitleCueExtractor.isBitmapSubtitle(srt))
    }

    @Test
    fun selectBestTextTrack_prefersLanguageAndId() {
        val selected = Format.Builder()
            .setId("2")
            .setLanguage("eng")
            .setLabel("English")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_SSA)
            .build()
        val tracks = listOf(
            EmbeddedSubtitleCueExtractor.CollectedTextTrack(
                format = Format.Builder()
                    .setId("1")
                    .setLanguage("spa")
                    .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                    .setCodecs(MimeTypes.APPLICATION_SUBRIP)
                    .build(),
                samples = emptyList(),
            ),
            EmbeddedSubtitleCueExtractor.CollectedTextTrack(
                format = Format.Builder()
                    .setId("2")
                    .setLanguage("eng")
                    .setLabel("English")
                    .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                    .setCodecs(MimeTypes.TEXT_SSA)
                    .build(),
                samples = emptyList(),
            ),
        )

        val matched = EmbeddedSubtitleCueExtractor.selectBestTextTrack(
            tracks = tracks,
            selectedFormat = selected,
            preferredTextTrackIndex = 0,
        )

        assertEquals("2", matched?.format?.id)
        assertEquals("en", matched?.format?.language)
    }

    @Test
    fun cuesWithTimingToTimedCues_joinsTextAndStripsMarkup() {
        val cues = listOf(
            Cue.Builder().setText("Hello <i>world</i>").build(),
            Cue.Builder().setText("{\\an8}Line two").build(),
        )
        val timed = CuesWithTiming(
            cues,
            /* startTimeUs= */ Util.msToUs(1_000),
            /* durationUs= */ Util.msToUs(2_000),
        )

        val result = EmbeddedSubtitleCueExtractor.cuesWithTimingToTimedCues(timed)

        assertEquals(1, result.size)
        assertEquals(1_000L, result[0].startMs)
        assertEquals(3_000L, result[0].endMs)
        assertEquals("Hello world\nLine two", result[0].text)
    }

    @Test
    fun cuesWithTimingToTimedCues_skipsBitmapOnlyCues() {
        val bitmapCue = Cue.Builder()
            .setBitmap(android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888))
            .build()
        val timed = CuesWithTiming(
            listOf(bitmapCue),
            Util.msToUs(500),
            Util.msToUs(500),
        )

        assertTrue(EmbeddedSubtitleCueExtractor.cuesWithTimingToTimedCues(timed).isEmpty())
    }

    @Test
    fun originalSubtitleMime_readsCodecsWhenTranscoded() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_SSA)
            .build()
        assertEquals(MimeTypes.TEXT_SSA, EmbeddedSubtitleCueExtractor.originalSubtitleMime(format))
    }

    @Test
    fun scoreTrack_boostsPreferredIndexWhenMetadataMissing() {
        val selected = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_SUBRIP)
            .build()
        val candidate = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_SUBRIP)
            .build()

        val scoreAtPreferred = EmbeddedSubtitleCueExtractor.scoreTrack(
            candidate = candidate,
            selected = selected,
            candidateIndex = 1,
            preferredIndex = 1,
        )
        val scoreElsewhere = EmbeddedSubtitleCueExtractor.scoreTrack(
            candidate = candidate,
            selected = selected,
            candidateIndex = 0,
            preferredIndex = 1,
        )

        assertTrue(scoreAtPreferred > scoreElsewhere)
    }
}
