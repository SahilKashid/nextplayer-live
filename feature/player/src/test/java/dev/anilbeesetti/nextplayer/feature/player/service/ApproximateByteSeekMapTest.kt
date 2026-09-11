package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ApproximateByteSeekMapTest {

    @Test
    fun isSeekable_requiresDurationAndPositiveTip() {
        val unset = ApproximateByteSeekMap(C.TIME_UNSET, tipProvider = { 1000L })
        assertFalse(unset.isSeekable)

        val noTip = ApproximateByteSeekMap(60_000_000L, tipProvider = { 0L })
        assertFalse(noTip.isSeekable)

        val ok = ApproximateByteSeekMap(60_000_000L, tipProvider = { 1000L })
        assertTrue(ok.isSeekable)
        assertTrue(ok.isEstimated)
        assertEquals(60_000_000L, ok.durationUs)
    }

    @Test
    fun getSeekPoints_clampsToTip_whenMappedPastTip() {
        // Full movie duration; only 10% downloaded (tip << declared).
        val durationUs = 100_000_000L
        val tip = 1_000_000L
        val declared = 10_000_000L
        val map = ApproximateByteSeekMap(
            durationUs = durationUs,
            tipProvider = { tip },
            declaredProvider = { declared },
        )
        // Mid-timeline: declared mapping would be 5_000_000, clamped to tip-1.
        val mid = map.getSeekPoints(durationUs / 2)
        assertEquals(tip - 1L, mid.first.position)
        assertEquals(durationUs / 2, mid.first.timeUs)

        // Near end of timeline: still clamps to tip.
        val end = map.getSeekPoints(durationUs - 1)
        assertEquals(tip - 1L, end.first.position)
    }

    @Test
    fun getSeekPoints_usesDeclaredMapping_whenWithinTip() {
        val durationUs = 100_000_000L
        val tip = 8_000_000L
        val declared = 10_000_000L
        val map = ApproximateByteSeekMap(
            durationUs = durationUs,
            tipProvider = { tip },
            declaredProvider = { declared },
        )
        // 20% of timeline → 20% of declared = 2_000_000, within tip.
        val points = map.getSeekPoints(20_000_000L)
        assertEquals(2_000_000L, points.first.position)
        assertEquals(20_000_000L, points.first.timeUs)
    }

    @Test
    fun getSeekPoints_fallsBackToTipMapping_whenDeclaredUnknown() {
        val durationUs = 100_000_000L
        val tip = 5_000_000L
        val map = ApproximateByteSeekMap(
            durationUs = durationUs,
            tipProvider = { tip },
            declaredProvider = { -1L },
        )
        val points = map.getSeekPoints(50_000_000L)
        assertEquals(tip / 2, points.first.position)
    }

    @Test
    fun getSeekPoints_startIsZero() {
        val map = ApproximateByteSeekMap(
            durationUs = 60_000_000L,
            tipProvider = { 4_000_000L },
            declaredProvider = { 10_000_000L },
        )
        val points = map.getSeekPoints(0L)
        assertEquals(0L, points.first.position)
        assertEquals(0L, points.first.timeUs)
    }
}
