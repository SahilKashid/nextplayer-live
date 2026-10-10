package dev.anilbeesetti.nextplayer.feature.player.state

import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue

/**
 * Helpers for live-subtitles panel behavior when the media item or selected text
 * track signature changes (next/prev / new URI / track reselect).
 *
 * Progressive cue fills **within** the same signature must not reset scroll or
 * clear highlight identities (anti-stutter). Across signatures, scroll/follow
 * and cue identities must start fresh — then immediately re-anchor to the new
 * playhead via [nearestCueIndexByPlayhead] (not a dumb always-to-top scroll).
 */
internal object LiveSubtitlesTrackChange {
    /** True when [newSignature] is a different media/track than [lastSignature]. */
    fun shouldResetForSignature(lastSignature: String?, newSignature: String): Boolean =
        lastSignature != newSignature

    /**
     * Index of the cue that should be scrolled/highlighted for [playheadMs]
     * without requiring an active (presenting) cue.
     *
     * Assumes [cues] are sorted by [TimedCue.startMs] ascending.
     * 1. Cue that contains playhead (`startMs <= playhead < endMs`)
     * 2. Else last cue with `startMs <= playhead`
     * 3. Else first cue with `startMs >= playhead`
     * 4. Else `0`
     *
     * Empty [cues] returns `-1` (callers should guard).
     */
    fun nearestCueIndexByPlayhead(cues: List<TimedCue>, playheadMs: Long): Int {
        if (cues.isEmpty()) return -1
        val containing = cues.indexOfLast { playheadMs >= it.startMs && playheadMs < it.endMs }
        if (containing >= 0) return containing
        val lastStarted = cues.indexOfLast { it.startMs <= playheadMs }
        if (lastStarted >= 0) return lastStarted
        val firstUpcoming = cues.indexOfFirst { it.startMs >= playheadMs }
        if (firstUpcoming >= 0) return firstUpcoming
        return 0
    }

    /**
     * Values applied when resetting for a new signature.
     * Panel visibility is intentionally omitted — it stays open across videos.
     * Scroll/highlight are cleared here; the first cue-list apply re-anchors via
     * [nearestCueIndexByPlayhead] from the new playhead.
     */
    data class ResetSnapshot(
        val listResetKey: String,
        val isLoading: Boolean,
        val isFollowing: Boolean = true,
        val currentCueIndex: Int = -1,
        val scrollTargetIndex: Int = -1,
        val highlightedCueKey: String? = null,
        val scrollTargetKey: String? = null,
        val cuesEmpty: Boolean = true,
        val isUnsupportedTrack: Boolean = false,
    )

    fun resetSnapshotForSignature(signature: String): ResetSnapshot =
        ResetSnapshot(
            listResetKey = signature,
            isLoading = signature != "none",
        )
}
