package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCueParserTest {

    @Test
    fun parseSrt_readsCueTimeline() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            Hello <i>world</i>

            2
            00:00:04,000 --> 00:00:05,000
            Second line
            continues here
        """.trimIndent()

        val cues = SubtitleCueParser.parseSrt(srt)

        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("Hello world", cues[0].text)
        assertEquals(4000L, cues[1].startMs)
        assertEquals(5000L, cues[1].endMs)
        assertEquals("Second line\ncontinues here", cues[1].text)
    }

    @Test
    fun parseVtt_supportsOptionalHoursAndTags() {
        val vtt = """
            WEBVTT

            NOTE comment

            00:01.000 --> 00:02.500
            <v Speaker>First cue

            00:00:03.000 --> 00:00:04.000
            Second <b>cue</b>
        """.trimIndent()

        val cues = SubtitleCueParser.parseVtt(vtt)

        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(2500L, cues[0].endMs)
        assertEquals("First cue", cues[0].text)
        assertEquals(3000L, cues[1].startMs)
        assertEquals(4000L, cues[1].endMs)
        assertEquals("Second cue", cues[1].text)
    }

    @Test
    fun parseVtt_realisticFile_withStyleNoteSettingsMultilineAndVoice() {
        val vtt = """
            WEBVTT - Generated demo

            STYLE
            ::cue {
              color: white;
              background-color: rgba(0,0,0,0.8);
            }

            NOTE
            This is a multiline
            note that must be ignored

            REGION
            id:banner
            width:40%

            1
            00:00:01.000 --> 00:00:02.500 align:start line:90%
            <v Alice>Hello there
            second line

            00:03.000 --> 00:04.500 position:10%
            <v Bob>World</v>

            NOTE trailing note

            00:00:05.000 --> 00:00:06.000
            Final cue
        """.trimIndent()

        val cues = SubtitleCueParser.parseVtt(vtt)

        assertEquals(3, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(2500L, cues[0].endMs)
        assertEquals("Hello there\nsecond line", cues[0].text)
        assertEquals(3000L, cues[1].startMs)
        assertEquals(4500L, cues[1].endMs)
        assertEquals("World", cues[1].text)
        assertEquals(5000L, cues[2].startMs)
        assertEquals("Final cue", cues[2].text)
    }

    @Test
    fun parseVtt_withoutWebvttHeader() {
        val vtt = """
            00:01.000 --> 00:02.000
            Headerless cue
        """.trimIndent()

        val cues = SubtitleCueParser.parse(vtt, mimeType = MimeTypes.TEXT_VTT)
        assertEquals(1, cues.size)
        assertEquals("Headerless cue", cues[0].text)
        assertEquals(1000L, cues[0].startMs)
    }

    @Test
    fun parseVtt_withBomAndCrLf() {
        val body = "WEBVTT\r\n\r\n00:00:00.500 --> 00:00:01.500\r\nBOM cue\r\n"
        val withBom = "\uFEFF$body"
        val cues = SubtitleCueParser.parse(withBom, MimeTypes.TEXT_VTT)
        assertEquals(1, cues.size)
        assertEquals(500L, cues[0].startMs)
        assertEquals(1500L, cues[0].endMs)
        assertEquals("BOM cue", cues[0].text)
    }

    @Test
    fun parse_dispatchesByMimeType() {
        val srt = """
            1
            00:00:00,000 --> 00:00:01,000
            SRT
        """.trimIndent()
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:01.000
            VTT
        """.trimIndent()

        assertEquals("SRT", SubtitleCueParser.parse(srt, MimeTypes.APPLICATION_SUBRIP).single().text)
        assertEquals("VTT", SubtitleCueParser.parse(vtt, MimeTypes.TEXT_VTT).single().text)
        // Wrong URI mime (common for content:// without extension) still sniffs WEBVTT.
        assertEquals("VTT", SubtitleCueParser.parse(vtt, MimeTypes.APPLICATION_SUBRIP).single().text)
    }

    @Test
    fun parse_unsupportedMimeReturnsEmpty() {
        val content = """
            [Script Info]
            Title: demo
        """.trimIndent()
        assertTrue(SubtitleCueParser.parse(content, MimeTypes.TEXT_SSA).isEmpty())
    }

    @Test
    fun parseTimestamps_acceptCommaAndDot() {
        assertEquals(3661001L, SubtitleCueParser.parseSrtTimestamp("01:01:01,001"))
        assertEquals(61001L, SubtitleCueParser.parseVttTimestamp("01:01.001"))
        assertEquals(3661001L, SubtitleCueParser.parseVttTimestamp("01:01:01.001"))
    }
}
