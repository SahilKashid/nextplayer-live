package dev.anilbeesetti.nextplayer.core.media.network.datasource

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ReadableTipTrackerTest {

    @Before
    @After
    fun clear() {
        ReadableTipTracker.clearAll()
    }

    @Test
    fun updateAndTipFor_roundTrip() {
        ReadableTipTracker.update("/tmp/a.mkv", readableEnd = 1234L, declaredLength = 9999L)
        assertEquals(1234L, ReadableTipTracker.tipFor("/tmp/a.mkv"))
        assertEquals(9999L, ReadableTipTracker.declaredFor("/tmp/a.mkv"))
        assertEquals(1234L, ReadableTipTracker.infoFor("/tmp/a.mkv")!!.readableEnd)
    }

    @Test
    fun tipFor_unknownReturnsMinusOne() {
        assertEquals(-1L, ReadableTipTracker.tipFor("missing"))
        assertEquals(-1L, ReadableTipTracker.declaredFor("missing"))
        assertNull(ReadableTipTracker.infoFor("missing"))
    }

    @Test
    fun clear_removesKey() {
        ReadableTipTracker.update("k", 10L, 20L)
        ReadableTipTracker.clear("k")
        assertEquals(-1L, ReadableTipTracker.tipFor("k"))
    }

    @Test
    fun update_ignoresNegativeReadableEnd() {
        ReadableTipTracker.update("k", 50L, 100L)
        ReadableTipTracker.update("k", -1L, 100L)
        assertEquals(50L, ReadableTipTracker.tipFor("k"))
    }
}
