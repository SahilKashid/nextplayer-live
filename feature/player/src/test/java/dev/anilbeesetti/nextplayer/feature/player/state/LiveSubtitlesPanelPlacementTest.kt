package dev.anilbeesetti.nextplayer.feature.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSubtitlesPanelPlacementTest {

    @Test
    fun cycleVisitsRightThenLeftThenOff() {
        val first = LiveSubtitlesPanelPlacement.OFF.cycle()
        val second = first.cycle()
        val third = second.cycle()

        assertEquals(LiveSubtitlesPanelPlacement.RIGHT, first)
        assertEquals(LiveSubtitlesPanelPlacement.LEFT, second)
        assertEquals(LiveSubtitlesPanelPlacement.OFF, third)
        assertEquals(LiveSubtitlesPanelPlacement.RIGHT, third.cycle())
    }

    @Test
    fun closedPanelIgnoresRememberedSide() {
        assertEquals(
            LiveSubtitlesPanelPlacement.OFF,
            LiveSubtitlesPanelPlacement.from(open = false, onLeft = true),
        )
        assertEquals(
            LiveSubtitlesPanelPlacement.RIGHT,
            LiveSubtitlesPanelPlacement.from(open = false, onLeft = true).cycle(),
        )
    }

    @Test
    fun openPanelRestoresTheSide() {
        assertEquals(
            LiveSubtitlesPanelPlacement.RIGHT,
            LiveSubtitlesPanelPlacement.from(open = true, onLeft = false),
        )
        assertEquals(
            LiveSubtitlesPanelPlacement.LEFT,
            LiveSubtitlesPanelPlacement.from(open = true, onLeft = true),
        )
    }

    @Test
    fun flagsMatchThePlacement() {
        assertEquals(false, LiveSubtitlesPanelPlacement.OFF.isOpen)
        assertEquals(false, LiveSubtitlesPanelPlacement.OFF.onLeft)
        assertEquals(true, LiveSubtitlesPanelPlacement.RIGHT.isOpen)
        assertEquals(false, LiveSubtitlesPanelPlacement.RIGHT.onLeft)
        assertEquals(true, LiveSubtitlesPanelPlacement.LEFT.isOpen)
        assertEquals(true, LiveSubtitlesPanelPlacement.LEFT.onLeft)
    }
}
