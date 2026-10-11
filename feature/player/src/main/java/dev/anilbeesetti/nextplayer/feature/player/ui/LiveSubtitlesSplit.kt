package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Same width on the left and the right. The player takes the rest. */
internal const val LiveSubtitlesPanelFraction = 0.35f

internal const val LiveSubtitlesPanelTag = "live_subtitles_panel"
internal const val LiveSubtitlesPlayerTag = "live_subtitles_player"

private const val PanelSlot = "panel"
private const val PlayerSlot = "player"

/**
 * One layout for both docks. The panel is [LiveSubtitlesPanelFraction] of the width
 * and the player is the rest, placed on the physical left or right. Both are measured
 * to those rectangles, so the panel cannot wrap to a button and the player cannot
 * draw over it.
 */
@Composable
internal fun LiveSubtitlesSplit(
    panelVisible: Boolean,
    panelOnLeft: Boolean,
    modifier: Modifier = Modifier,
    panel: @Composable () -> Unit,
    player: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(
                Modifier
                    .layoutId(PlayerSlot)
                    .clipToBounds()
                    .testTag(LiveSubtitlesPlayerTag),
            ) {
                player()
            }
            if (panelVisible) {
                Box(
                    Modifier
                        .layoutId(PanelSlot)
                        .clipToBounds()
                        .testTag(LiveSubtitlesPanelTag),
                ) {
                    panel()
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val panelWidth = if (panelVisible) {
            (width * LiveSubtitlesPanelFraction).roundToInt().coerceIn(0, width)
        } else {
            0
        }
        val playerWidth = width - panelWidth
        val playerPlaceable = measurables
            .first { it.layoutId == PlayerSlot }
            .measure(Constraints.fixed(playerWidth, height))
        val panelPlaceable = measurables
            .firstOrNull { it.layoutId == PanelSlot }
            ?.measure(Constraints.fixed(panelWidth, height))
        layout(width, height) {
            val playerX = if (panelVisible && panelOnLeft) panelWidth else 0
            playerPlaceable.place(playerX, 0)
            panelPlaceable?.place(if (panelOnLeft) 0 else playerWidth, 0)
        }
    }
}
