package dev.anilbeesetti.nextplayer.feature.player.state

/**
 * Helpers for live-subtitles panel behavior when the media item or selected text
 * track signature changes (next/prev / new URI / track reselect).
 *
 * Progressive cue fills **within** the same signature must not reset scroll or
 * clear highlight identities (anti-stutter). Across signatures, scroll/follow
 * and cue identities must start fresh.
 */
internal object LiveSubtitlesTrackChange {
    /** True when [newSignature] is a different media/track than [lastSignature]. */
    fun shouldResetForSignature(lastSignature: String?, newSignature: String): Boolean =
        lastSignature != newSignature

    /**
     * Values applied when resetting for a new signature.
     * Panel visibility is intentionally omitted — it stays open across videos.
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
