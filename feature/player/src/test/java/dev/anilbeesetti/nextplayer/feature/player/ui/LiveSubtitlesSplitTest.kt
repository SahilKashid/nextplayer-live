package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val PanelChildTag = "panel_child"
private const val PlayerChildTag = "player_child"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveSubtitlesSplitTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun closedPanelKeepsEveryInsetOnTheVideo() {
        val window = ScreenEdgeInsets(left = 10, top = 24, right = 48, bottom = 16)
        val split = liveSubtitlesSplitPadding(window, panelVisible = false, panelOnLeft = true)

        assertEquals(ScreenEdgeInsets(), split.panel)
        assertEquals(window, split.content)
    }

    @Test
    fun leftDockGivesTheLeftEdgeToThePanelAndTheRightEdgeToTheVideo() {
        val window = ScreenEdgeInsets(left = 80, top = 24, right = 36, bottom = 12)
        val split = liveSubtitlesSplitPadding(window, panelVisible = true, panelOnLeft = true)

        assertEquals(ScreenEdgeInsets(left = 80, top = 24, right = 0, bottom = 12), split.panel)
        assertEquals(ScreenEdgeInsets(left = 0, top = 24, right = 36, bottom = 12), split.content)
    }

    @Test
    fun rightDockMirrorsTheHorizontalInsets() {
        val window = ScreenEdgeInsets(left = 80, top = 24, right = 36, bottom = 12)
        val split = liveSubtitlesSplitPadding(window, panelVisible = true, panelOnLeft = false)

        assertEquals(ScreenEdgeInsets(left = 0, top = 24, right = 36, bottom = 12), split.panel)
        assertEquals(ScreenEdgeInsets(left = 80, top = 24, right = 0, bottom = 12), split.content)
    }

    @Test
    fun aCutoutOnEitherSideStaysOnThatPhysicalEdge() {
        val leftCutout = ScreenEdgeInsets(left = 90)
        val leftDock = liveSubtitlesSplitPadding(leftCutout, panelVisible = true, panelOnLeft = true)
        assertEquals(90, leftDock.panel.left)
        assertEquals(0, leftDock.content.left)
        assertEquals(0, leftDock.panel.right)

        val rightDock = liveSubtitlesSplitPadding(leftCutout, panelVisible = true, panelOnLeft = false)
        assertEquals(0, rightDock.panel.left)
        assertEquals(90, rightDock.content.left)

        val rightCutout = ScreenEdgeInsets(right = 40)
        val rightDockWithRightCutout = liveSubtitlesSplitPadding(rightCutout, panelVisible = true, panelOnLeft = false)
        assertEquals(40, rightDockWithRightCutout.panel.right)
        assertEquals(0, rightDockWithRightCutout.content.right)

        val leftDockWithRightCutout = liveSubtitlesSplitPadding(rightCutout, panelVisible = true, panelOnLeft = true)
        assertEquals(0, leftDockWithRightCutout.panel.right)
        assertEquals(40, leftDockWithRightCutout.content.right)
    }

    @Test
    fun leftAndRightDocksUseTheSamePanelWidthOnOppositeEdges() {
        var panelOnLeft by mutableStateOf(true)
        composeRule.setContent {
            LiveSubtitlesSplit(
                panelVisible = true,
                panelOnLeft = panelOnLeft,
                modifier = Modifier.fillMaxSize(),
                panel = { Box(Modifier.size(48.dp).testTag(PanelChildTag)) },
                player = { Box(Modifier.fillMaxSize().testTag(PlayerChildTag)) },
            )
        }

        val leftPanel = bounds(LiveSubtitlesPanelTag)
        val leftPlayer = bounds(LiveSubtitlesPlayerTag)
        assertDock(leftPanel, leftPlayer, panelOnLeft = true)
        assertTrue(bounds(PanelChildTag).right <= leftPanel.right + 1f)

        panelOnLeft = false
        composeRule.waitForIdle()

        val rightPanel = bounds(LiveSubtitlesPanelTag)
        val rightPlayer = bounds(LiveSubtitlesPlayerTag)
        assertDock(rightPanel, rightPlayer, panelOnLeft = false)
        assertEquals(leftPanel.width, rightPanel.width, 1f)
        assertEquals(leftPlayer.width, rightPlayer.width, 1f)
    }

    @Test
    fun hiddenPanelLeavesTheVideoFullWidth() {
        composeRule.setContent {
            LiveSubtitlesSplit(
                panelVisible = false,
                panelOnLeft = true,
                modifier = Modifier.fillMaxSize(),
                panel = { Box(Modifier.fillMaxSize()) },
                player = { Box(Modifier.fillMaxSize().testTag(PlayerChildTag)) },
            )
        }

        composeRule.onNodeWithTag(LiveSubtitlesPanelTag).assertDoesNotExist()
        val player = bounds(LiveSubtitlesPlayerTag)
        val child = bounds(PlayerChildTag)
        assertEquals(0f, player.left, 1f)
        assertEquals(player.left, child.left, 1f)
        assertEquals(player.right, child.right, 1f)
        assertTrue(player.width > 0f)
    }

    private fun assertDock(panel: PxRect, player: PxRect, panelOnLeft: Boolean) {
        val total = panel.width + player.width
        assertEquals(LiveSubtitlesPanelFraction, panel.width / total, 0.01f)
        assertEquals(panel.top, player.top, 1f)
        assertEquals(panel.bottom, player.bottom, 1f)
        assertTrue(player.width > panel.width)
        if (panelOnLeft) {
            assertEquals(0f, panel.left, 1f)
            assertEquals(panel.right, player.left, 1f)
        } else {
            assertEquals(0f, player.left, 1f)
            assertEquals(player.right, panel.left, 1f)
        }
    }

    private fun bounds(tag: String): PxRect {
        val rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return PxRect(rect.left, rect.top, rect.right, rect.bottom)
    }

    private data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
    }
}
