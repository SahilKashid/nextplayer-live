package dev.anilbeesetti.nextplayer.feature.player.state

/**
 * Live-subtitles button cycle: off → docked on the right → docked on the left → off.
 *
 * A closed panel always starts the next cycle on the right. [onLeft] is only restored
 * while the panel is still open (pause / lock / recreate), matching [liveSubtitlesPanelOpen].
 */
enum class LiveSubtitlesPanelPlacement {
    OFF,
    RIGHT,
    LEFT,
    ;

    fun cycle(): LiveSubtitlesPanelPlacement = when (this) {
        OFF -> RIGHT
        RIGHT -> LEFT
        LEFT -> OFF
    }

    val isOpen: Boolean
        get() = this != OFF

    val onLeft: Boolean
        get() = this == LEFT

    companion object {
        fun from(open: Boolean, onLeft: Boolean): LiveSubtitlesPanelPlacement = when {
            !open -> OFF
            onLeft -> LEFT
            else -> RIGHT
        }
    }
}
