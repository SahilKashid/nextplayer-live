package dev.anilbeesetti.nextplayer.feature.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerZoomPanTest {

    @Test
    fun panFractionStaysInsideThePlayerSlotWhenTheSlotNarrows() {
        val fullScreenWidth = 1000
        val besidePanelWidth = 650
        val zoom = 2f
        val fraction = 0.5f

        val fullScreenOffset = PlayerZoomPan.offsetPx(fraction, zoom, fullScreenWidth)
        val besidePanelOffset = PlayerZoomPan.offsetPx(fraction, zoom, besidePanelWidth)

        assertEquals(250f, fullScreenOffset, 0.001f)
        assertEquals(162.5f, besidePanelOffset, 0.001f)
        assertEquals(fraction, PlayerZoomPan.fraction(besidePanelOffset, zoom, besidePanelWidth), 0.001f)
        assertEquals(0f, PlayerZoomPan.offsetPx(fraction, zoom = 1f, sizePx = fullScreenWidth), 0.001f)
    }

    @Test
    fun fractionAndZoomStayInRange() {
        assertEquals(1f, PlayerZoomPan.clampFraction(4f), 0.001f)
        assertEquals(-1f, PlayerZoomPan.clampFraction(-2f), 0.001f)
        assertEquals(PlayerZoomPan.MAX_ZOOM, PlayerZoomPan.clampZoom(9f), 0.001f)
        assertEquals(PlayerZoomPan.MIN_ZOOM, PlayerZoomPan.clampZoom(0.01f), 0.001f)
        assertEquals(0f, PlayerZoomPan.fraction(offsetPx = 40f, zoom = 1f, sizePx = 800), 0.001f)
    }
}
