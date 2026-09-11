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
    fun getSeekPoints_midScrubStaysMidSafeTip_whenDeclaredMuchLarger() {
        // Full movie duration; only ~10% downloaded (tip << declared).
        // Tip-relative mapping (not declared-then-clamp) keeps mid scrub at mid safeTip.
        val durationUs = 100_000_000L
        val tip = 1_000_000L
        val declared = 10_000_000L
        val map = ApproximateByteSeekMap(
            durationUs = durationUs,
            tipProvider = { tip },
            declaredProvider = { declared },
        )
        val safe = ApproximateByteSeekMap.safeTip(tip)
        val mid = map.getSeekPoints(durationUs / 2)
        assertEquals(safe / 2, mid.first.position)
        assertEquals(durationUs / 2, mid.first.timeUs)
        // Must NOT clamp to tip-1 (that lands in the unfinished edge).
        assertTrue(mid.first.position < tip - 1L)
        assertTrue(mid.first.position < safe)
    }

    @Test
    fun getSeekPoints_nearEndMapsNearSafeTip_notDeclaredEdge() {
        val durationUs = 100_000_000L
        val tip = 1_000_000L
        val declared = 10_000_000L
        val map = ApproximateByteSeekMap(
            durationUs = durationUs,
            tipProvider = { tip },
            declaredProvider = { declared },
        )
        val safe = ApproximateByteSeekMap.safeTip(tip)
        val end = map.getSeekPoints(durationUs - 1)
        assertEquals(safe - 1L, end.first.position)
    }

    @Test
    fun getSeekPoints_usesTipRelativeMapping_evenWhenDeclaredKnown() {
        val durationUs = 100_000_000L
        val tip = 8_000_000L
        val declared = 10_000_000L
        val map = ApproximateByteSeekMap(
            durationUs = durationUs,
            tipProvider = { tip },
            declaredProvider = { declared },
        )
        val safe = ApproximateByteSeekMap.safeTip(tip)
        // 20% of timeline → 20% of safeTip (not 20% of declared).
        val points = map.getSeekPoints(20_000_000L)
        assertEquals((20_000_000L * safe) / durationUs, points.first.position)
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
        val safe = ApproximateByteSeekMap.safeTip(tip)
        val points = map.getSeekPoints(50_000_000L)
        assertEquals(safe / 2, points.first.position)
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

    @Test
    fun safeTip_appliesOneMiBOrTipOverEight() {
        assertEquals(0L, ApproximateByteSeekMap.safeTip(0L))
        // tip/8 < 1MiB → margin = tip/8
        assertEquals(7_000_000L, ApproximateByteSeekMap.safeTip(8_000_000L))
        // large tip → margin capped at 1MiB
        assertEquals(9L * 1024L * 1024L, ApproximateByteSeekMap.safeTip(10L * 1024L * 1024L))
    }
}
