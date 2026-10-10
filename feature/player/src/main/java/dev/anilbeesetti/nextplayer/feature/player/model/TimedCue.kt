package dev.anilbeesetti.nextplayer.feature.player.model

/**
 * A timed subtitle cue for the live subtitles panel timeline.
 */
data class TimedCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
