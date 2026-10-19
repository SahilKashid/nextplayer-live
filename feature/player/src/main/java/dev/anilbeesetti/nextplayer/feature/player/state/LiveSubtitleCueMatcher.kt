package dev.anilbeesetti.nextplayer.feature.player.state

import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import kotlin.math.abs

/**
 * Pure cue-text matching for the live-subtitles highlight.
 *
 * OOM fix (1.1.3 crash, SM-S942B): the previous implementation lived in
 * `LiveSubtitlesState.matchCuesToIndex` and called `normalizeCueText()` for
 * **every** cue on **every** ~50 ms highlight tick (and on every EVENT_CUES).
 * `normalizeCueText` built three fresh [Regex] objects per call, so a
 * 1,500-cue film compiled ~4,500 patterns (plus three new strings per cue)
 * twenty times a second on the main thread. The R8-retraced stack was:
 * `Pattern.matcher -> LiveSubtitlesStateKt.normalizeCueText:605 ->
 * LiveSubtitlesState.matchCuesToIndex -> updateCurrentCueIndexFromPlayer ->
 * LiveSubtitlesState$observe$2`.
 *
 * This matcher therefore:
 * 1. compiles each pattern **once** (object init), and
 * 2. lets the caller cache the normalized timeline once per cue-list
 *    instance ([normalizedTexts]) and reuse it across ticks — a tick then
 *    only normalizes the 1-2 active player cues and does cheap string
 *    comparisons against the cached list.
 *
 * Matching semantics are unchanged from the original code: exact normalized
 * match first, soft `contains` match only when [allowSoftMatch] (loading
 * finished), and duplicate texts disambiguated by distance of the cue
 * midpoint to [effectivePositionMs].
 */
internal object LiveSubtitleCueMatcher {

    private val Whitespace = Regex("""\s+""")
    private val HtmlTag = Regex("""</?[^>]+>""")
    private val AssOverrideTag = Regex("""\{[^}]*\}""")

    fun normalizeCueText(text: String): String =
        text.trim()
            .replace(Whitespace, " ")
            .replace(HtmlTag, "")
            .replace(AssOverrideTag, "")

    /** Normalized text for every cue, in cue order. Cache this per cue list. */
    fun normalizedTexts(cues: List<TimedCue>): List<String> =
        cues.map { normalizeCueText(it.text) }

    /**
     * Index of the cue matching one of [activeTexts] (already normalized,
     * non-empty), or -1 when nothing matches.
     *
     * [normalizedCueTexts] must be [normalizedTexts] of [cues] (same size /
     * order); when it is not, it is rebuilt here so callers can never match
     * against a stale timeline.
     */
    fun matchIndex(
        cues: List<TimedCue>,
        normalizedCueTexts: List<String>,
        activeTexts: List<String>,
        effectivePositionMs: Long,
        allowSoftMatch: Boolean,
    ): Int {
        if (cues.isEmpty() || activeTexts.isEmpty()) return -1
        val normalized = if (normalizedCueTexts.size == cues.size) {
            normalizedCueTexts
        } else {
            normalizedTexts(cues)
        }
        val joined = activeTexts.joinToString("\n")

        val candidates = ArrayList<Int>()
        for (i in cues.indices) {
            val text = normalized[i]
            if (text == joined || activeTexts.any { it == text }) {
                candidates += i
            }
        }
        // Soft contains matching can oscillate across progressive partial
        // lists; only soft-match once loading is finished (caller decides).
        if (candidates.isEmpty() && allowSoftMatch) {
            for (i in cues.indices) {
                val text = normalized[i]
                if (activeTexts.any { active ->
                        text.contains(active) || active.contains(text)
                    }
                ) {
                    candidates += i
                }
            }
        }
        if (candidates.isEmpty()) return -1
        if (candidates.size == 1) return candidates[0]

        // Disambiguate duplicate texts near the delay-adjusted position.
        return candidates.minBy { idx ->
            val cue = cues[idx]
            abs((cue.startMs + cue.endMs) / 2L - effectivePositionMs)
        }
    }
}
