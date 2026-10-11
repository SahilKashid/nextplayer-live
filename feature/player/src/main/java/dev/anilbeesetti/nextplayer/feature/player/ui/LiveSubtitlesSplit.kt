package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

/** Same width on the left and the right. The player takes the rest. */
internal const val LiveSubtitlesPanelFraction = 0.35f

internal const val LiveSubtitlesPanelTag = "live_subtitles_panel"
internal const val LiveSubtitlesPlayerTag = "live_subtitles_player"

private const val PanelSlot = "panel"
private const val PlayerSlot = "player"

/**
 * Chrome padding for the video slot. Null means "read the full window insets",
 * which is the closed-panel path. A value already excludes the inset owned by the panel.
 */
internal val LocalPlayerChromePadding = compositionLocalOf<PaddingValues?> { null }

/**
 * Padding for controls inside the video slot. While the panel is open this is the
 * slot's own edges. While it is closed, callers keep the full window insets.
 */
@Composable
internal fun playerChromePadding(): PaddingValues {
    LocalPlayerChromePadding.current?.let { return it }
    return WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
}

/**
 * Physical screen insets in pixels. Left and right are screen edges, not start/end,
 * so a landscape cutout stays on the side where it is drawn.
 */
internal data class ScreenEdgeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

internal data class LiveSubtitlesSplitPadding(
    val panel: ScreenEdgeInsets,
    val content: ScreenEdgeInsets,
)

/**
 * Status and navigation bars run across the top and bottom, so both panes keep them.
 * A left or right inset (cutout, or a side system bar) belongs only to the pane
 * that touches that screen edge. With the panel closed, the video keeps every inset.
 */
internal fun liveSubtitlesSplitPadding(
    window: ScreenEdgeInsets,
    panelVisible: Boolean,
    panelOnLeft: Boolean,
): LiveSubtitlesSplitPadding {
    if (!panelVisible) {
        return LiveSubtitlesSplitPadding(panel = ScreenEdgeInsets(), content = window)
    }
    return if (panelOnLeft) {
        LiveSubtitlesSplitPadding(
            panel = window.copy(right = 0),
            content = window.copy(left = 0),
        )
    } else {
        LiveSubtitlesSplitPadding(
            panel = window.copy(left = 0),
            content = window.copy(right = 0),
        )
    }
}

internal fun ScreenEdgeInsets.toPaddingValues(density: Density): PaddingValues = with(density) {
    PaddingValues.Absolute(
        left = left.toDp(),
        top = top.toDp(),
        right = right.toDp(),
        bottom = bottom.toDp(),
    )
}

/**
 * One layout for both docks. The panel is [LiveSubtitlesPanelFraction] of the width
 * and the player is the rest, placed on the physical left or right. Children are
 * measured to those rectangles so neither can wrap to a button width or draw over
 * the other.
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

@Composable
internal fun rememberLiveSubtitlesSplitPadding(
    panelVisible: Boolean,
    panelOnLeft: Boolean,
): LiveSubtitlesSplitPadding {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val window = ScreenEdgeInsets(
        left = windowInsets.getLeft(density, layoutDirection),
        top = windowInsets.getTop(density),
        right = windowInsets.getRight(density, layoutDirection),
        bottom = windowInsets.getBottom(density),
    )
    return liveSubtitlesSplitPadding(
        window = window,
        panelVisible = panelVisible,
        panelOnLeft = panelOnLeft,
    )
}
