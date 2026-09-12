package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class ExpandingCueWindowTest {

    @Test
    fun seedWindow_centersNearPlaybackWithLead() {
        val seed = ExpandingCueWindow.seedWindow(playbackPositionMs = 120_000L)
        assertEquals(120_000L - ExpandingCueWindow.NEAR_SEEK_LEAD_MS, seed.startMs)
        assertEquals(120_000L + ExpandingCueWindow.SEED_HALF_WINDOW_MS, seed.endMs)
    }

    @Test
    fun shouldUseExpandingWindow_requiresSeekableAndMidPlayback() {
        assertTrue(
            ExpandingCueWindow.shouldUseExpandingWindow(
                playbackPositionMs = ExpandingCueWindow.NEAR_FIRST_THRESHOLD_MS,
                seekable = true,
            ),
        )
        assertFalse(
            ExpandingCueWindow.shouldUseExpandingWindow(
                playbackPositionMs = ExpandingCueWindow.NEAR_FIRST_THRESHOLD_MS - 1,
                seekable = true,
            ),
        )
        assertFalse(
            ExpandingCueWindow.shouldUseExpandingWindow(
                playbackPositionMs = 60_000L,
                seekable = false,
            ),
        )
    }

    @Test
    fun nextEarlierWindow_stepsBackUntilZero() {
        val first = ExpandingCueWindow.nextEarlierWindow(
            coveredStartMs = 100_000L,
            stepMs = 45_000L,
        )
        assertEquals(ExpandingCueWindow.Window(55_000L, 100_000L), first)

        val nearZero = ExpandingCueWindow.nextEarlierWindow(
            coveredStartMs = 20_000L,
            stepMs = 45_000L,
        )
        assertEquals(ExpandingCueWindow.Window(0L, 20_000L), nearZero)

        assertNull(ExpandingCueWindow.nextEarlierWindow(coveredStartMs = 0L, stepMs = 45_000L))
    }

    @Test
    fun nextLaterWindow_skipsWhenEofReached() {
        val later = ExpandingCueWindow.nextLaterWindow(
            coveredEndMs = 100_000L,
            stepMs = 45_000L,
            reachedEof = false,
        )
        assertEquals(ExpandingCueWindow.Window(100_000L, 145_000L), later)
        assertNull(
            ExpandingCueWindow.nextLaterWindow(
                coveredEndMs = 100_000L,
                stepMs = 45_000L,
                reachedEof = true,
            ),
        )
    }

    @Test
    fun growStep_incrementsUntilCap() {
        val grown = ExpandingCueWindow.growStep(ExpandingCueWindow.INITIAL_EXPAND_STEP_MS)
        assertEquals(
            ExpandingCueWindow.INITIAL_EXPAND_STEP_MS + ExpandingCueWindow.EXPAND_STEP_INCREMENT_MS,
            grown,
        )
        assertEquals(
            ExpandingCueWindow.MAX_EXPAND_STEP_MS,
            ExpandingCueWindow.growStep(ExpandingCueWindow.MAX_EXPAND_STEP_MS),
        )
    }

    @Test
    fun mergeByIdentity_neverShrinksAndDedupes() {
        val into = mutableListOf(
            TimedCue(0L, 1000L, "A"),
            TimedCue(1000L, 2000L, "B"),
        )
        val seen = into.map { ExpandingCueWindow.cueIdentityKey(it) }.toMutableSet()
        val added = ExpandingCueWindow.mergeByIdentity(
            into = into,
            seen = seen,
            incoming = listOf(
                TimedCue(1000L, 2000L, "B"), // dup
                TimedCue(2000L, 3000L, "C"),
            ),
        )
        assertEquals(1, added)
        assertEquals(3, into.size)
        assertEquals(listOf("A", "B", "C"), into.map { it.text })
    }

    @Test
    fun planExpandWindows_alternatesEarlierThenLaterAndGrows() {
        val seed = ExpandingCueWindow.Window(startMs = 100_000L, endMs = 140_000L)
        val plan = ExpandingCueWindow.planExpandWindows(
            seed = seed,
            seedReachedEof = false,
            maxSteps = 3,
        )
        // First pair: earlier then later with initial step.
        assertEquals(ExpandingCueWindow.Window(55_000L, 100_000L), plan[0])
        assertEquals(ExpandingCueWindow.Window(140_000L, 185_000L), plan[1])
        // Second pair uses grown step (45s + 30s = 75s).
        assertEquals(ExpandingCueWindow.Window(0L, 55_000L), plan[2])
        assertTrue(plan[3].startMs == 185_000L)
        assertTrue(plan.size >= 4)
    }

    @Test
    fun planExpandWindows_stopsLaterWhenSeedAlreadyEof() {
        val seed = ExpandingCueWindow.Window(startMs = 80_000L, endMs = 120_000L)
        val plan = ExpandingCueWindow.planExpandWindows(
            seed = seed,
            seedReachedEof = true,
            maxSteps = 8,
        )
        // Only earlier windows until start.
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.endMs <= seed.startMs })
        assertEquals(0L, plan.last().startMs)
    }
}
