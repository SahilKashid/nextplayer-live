package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class SubtitleVerticalPositionTest {

    @Test
    fun defaultLiftKeepsMedia3BottomPadding() {
        assertEquals(0.08f, SubtitleVerticalPosition.bottomPaddingFraction(0f), 0.0001f)
    }

    @Test
    fun liftAddsToDefaultBottomPaddingAndClamps() {
        assertEquals(0.33f, SubtitleVerticalPosition.bottomPaddingFraction(0.25f), 0.0001f)
        assertEquals(0.58f, SubtitleVerticalPosition.bottomPaddingFraction(0.5f), 0.0001f)
        assertEquals(0.58f, SubtitleVerticalPosition.bottomPaddingFraction(0.9f), 0.0001f)
    }

    @Test
    fun defaultLiftLeavesCuesUntouched() {
        val vttAuto = Cue.Builder()
            .setText("Hello")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .build()

        val result = SubtitleVerticalPosition.applyToCues(listOf(vttAuto), 0f)

        assertSame(vttAuto, result.single())
    }

    @Test
    fun defaultPreferenceLiftAppliesToBottomAnchoredCues() {
        val vttAuto = Cue.Builder()
            .setText("Hello")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .build()

        val result = SubtitleVerticalPosition.applyToCues(listOf(vttAuto), 0.2f).single()

        // 1 - (0.08 + 0.2) = 0.72
        assertEquals(0.72f, result.line, 0.0001f)
        assertEquals(Cue.LINE_TYPE_FRACTION, result.lineType)
        assertEquals(Cue.ANCHOR_TYPE_END, result.lineAnchor)
    }

    @Test
    fun liftRewritesWebVttAutoLineToMatchingFraction() {
        val vttAuto = Cue.Builder()
            .setText("Hello")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .build()

        val result = SubtitleVerticalPosition.applyToCues(listOf(vttAuto), 0.25f).single()

        assertEquals(0.67f, result.line, 0.0001f)
        assertEquals(Cue.LINE_TYPE_FRACTION, result.lineType)
        assertEquals(Cue.ANCHOR_TYPE_END, result.lineAnchor)
        assertEquals("Hello", result.text.toString())
    }

    @Test
    fun liftRewritesNearBottomFractionCues() {
        val vttBottom = Cue.Builder()
            .setText("Bottom")
            .setLine(0.95f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .build()

        val result = SubtitleVerticalPosition.applyToCues(listOf(vttBottom), 0.5f).single()

        assertEquals(0.42f, result.line, 0.0001f)
        assertEquals(Cue.LINE_TYPE_FRACTION, result.lineType)
    }

    @Test
    fun liftLeavesIntentionallyHighCuesAlone() {
        val topCue = Cue.Builder()
            .setText("Top")
            .setLine(0.1f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build()

        val result = SubtitleVerticalPosition.applyToCues(listOf(topCue), 0.25f).single()

        assertEquals(0.1f, result.line, 0.0001f)
        assertFalse(SubtitleVerticalPosition.isBottomAnchored(topCue))
        assertTrue(SubtitleVerticalPosition.isBottomAnchored(Cue.Builder().setText("unset").build()))
    }
}
