package dev.anilbeesetti.nextplayer.feature.player.state

import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSubtitleCueMatcherTest {

    private val cues = listOf(
        TimedCue(startMs = 1_000L, endMs = 2_000L, text = "<i>Hello</i>   world"),
        TimedCue(startMs = 3_000L, endMs = 4_000L, text = "Same line"),
        TimedCue(startMs = 5_000L, endMs = 6_000L, text = "{\\an8}Same line"),
        TimedCue(startMs = 7_000L, endMs = 8_000L, text = "A longer subtitle line here"),
    )
    private val normalized = LiveSubtitleCueMatcher.normalizedTexts(cues)

    @Test
    fun normalize_stripsMarkupAndCollapsesWhitespace() {
        assertEquals("Hello world", LiveSubtitleCueMatcher.normalizeCueText("  <i>Hello</i>   world "))
        assertEquals("Same line", LiveSubtitleCueMatcher.normalizeCueText("{\\an8}Same line"))
    }

    @Test
    fun normalizedTexts_preservesOrderAndSize() {
        assertEquals(cues.size, normalized.size)
        assertEquals("Hello world", normalized[0])
        assertEquals("Same line", normalized[2])
    }

    @Test
    fun match_exactNormalizedText() {
        val index = LiveSubtitleCueMatcher.matchIndex(
            cues = cues,
            normalizedCueTexts = normalized,
            activeTexts = listOf("Hello world"),
            effectivePositionMs = 1_500L,
            allowSoftMatch = false,
        )
        assertEquals(0, index)
    }

    @Test
    fun match_duplicateText_picksNearestToPosition() {
        val nearSecond = LiveSubtitleCueMatcher.matchIndex(
            cues = cues,
            normalizedCueTexts = normalized,
            activeTexts = listOf("Same line"),
            effectivePositionMs = 5_400L,
            allowSoftMatch = false,
        )
        assertEquals(2, nearSecond)

        val nearFirst = LiveSubtitleCueMatcher.matchIndex(
            cues = cues,
            normalizedCueTexts = normalized,
            activeTexts = listOf("Same line"),
            effectivePositionMs = 3_400L,
            allowSoftMatch = false,
        )
        assertEquals(1, nearFirst)
    }

    @Test
    fun match_softContains_onlyWhenAllowed() {
        val softDenied = LiveSubtitleCueMatcher.matchIndex(
            cues = cues,
            normalizedCueTexts = normalized,
            activeTexts = listOf("longer subtitle"),
            effectivePositionMs = 7_500L,
            allowSoftMatch = false,
        )
        assertEquals(-1, softDenied)

        val softAllowed = LiveSubtitleCueMatcher.matchIndex(
            cues = cues,
            normalizedCueTexts = normalized,
            activeTexts = listOf("longer subtitle"),
            effectivePositionMs = 7_500L,
            allowSoftMatch = true,
        )
        assertEquals(3, softAllowed)
    }

    @Test
    fun match_noMatch_returnsMinusOne() {
        assertEquals(
            -1,
            LiveSubtitleCueMatcher.matchIndex(
                cues = cues,
                normalizedCueTexts = normalized,
                activeTexts = listOf("not present at all"),
                effectivePositionMs = 0L,
                allowSoftMatch = true,
            ),
        )
        assertEquals(
            -1,
            LiveSubtitleCueMatcher.matchIndex(
                cues = emptyList(),
                normalizedCueTexts = emptyList(),
                activeTexts = listOf("x"),
                effectivePositionMs = 0L,
                allowSoftMatch = true,
            ),
        )
    }

    @Test
    fun match_staleNormalizedList_isRebuiltNotTrusted() {
        // Wrong-size cache must not shift matches onto the wrong cue.
        val index = LiveSubtitleCueMatcher.matchIndex(
            cues = cues,
            normalizedCueTexts = listOf("stale"),
            activeTexts = listOf("Hello world"),
            effectivePositionMs = 1_500L,
            allowSoftMatch = false,
        )
        assertEquals(0, index)
    }

    @Test
    fun normalizedTexts_reusedAcrossTicks_isSameInstanceWhenCallerCaches() {
        // The state caches one list instance per cue-list instance; matching
        // must not mutate it (regression guard for the per-tick OOM path).
        val before = normalized.toList()
        LiveSubtitleCueMatcher.matchIndex(cues, normalized, listOf("Same line"), 3_400L, true)
        assertEquals(before, normalized)
    }
}
