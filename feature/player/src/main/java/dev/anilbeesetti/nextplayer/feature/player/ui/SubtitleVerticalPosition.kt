package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences

/**
 * Maps the subtitle vertical-position preference onto Media3 overlay layout.
 *
 * [androidx.media3.ui.SubtitleView.setBottomPaddingFraction] only moves cues whose
 * [Cue.line] is [Cue.DIMEN_UNSET]. WebVTT automatic / bottom `line` values are set by the
 * spec (typically `line = -1`, [Cue.LINE_TYPE_NUMBER]), so those cues are rewritten to a
 * matching fractional line when the user lifts the overlay.
 */
@UnstableApi
object SubtitleVerticalPosition {
    /** Matches [androidx.media3.ui.SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION]. */
    const val DEFAULT_BOTTOM_PADDING_FRACTION = 0.08f

    fun coerce(verticalPosition: Float): Float = verticalPosition.coerceIn(
        PlayerPreferences.DEFAULT_SUBTITLE_VERTICAL_POSITION,
        PlayerPreferences.MAX_SUBTITLE_VERTICAL_POSITION,
    )

    fun bottomPaddingFraction(verticalPosition: Float): Float =
        DEFAULT_BOTTOM_PADDING_FRACTION + coerce(verticalPosition)

    fun applyToCues(cues: List<Cue>, verticalPosition: Float): List<Cue> {
        val lift = coerce(verticalPosition)
        if (lift == PlayerPreferences.DEFAULT_SUBTITLE_VERTICAL_POSITION) {
            return cues
        }
        val lineFromTop = (1f - bottomPaddingFraction(lift)).coerceIn(0f, 1f)
        return cues.map { cue ->
            if (cue.bitmap != null || !isBottomAnchored(cue)) {
                cue
            } else {
                cue.buildUpon()
                    .setLine(lineFromTop, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_END)
                    .build()
            }
        }
    }

    fun isBottomAnchored(cue: Cue): Boolean {
        if (cue.line == Cue.DIMEN_UNSET) return true
        if (cue.lineType == Cue.LINE_TYPE_NUMBER && cue.line < 0f) return true
        if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line >= BOTTOM_FRACTION_THRESHOLD) return true
        return false
    }

    private const val BOTTOM_FRACTION_THRESHOLD = 0.85f
}
