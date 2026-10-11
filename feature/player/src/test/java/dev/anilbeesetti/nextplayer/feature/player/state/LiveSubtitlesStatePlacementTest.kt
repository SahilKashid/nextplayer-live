package dev.anilbeesetti.nextplayer.feature.player.state

import android.os.Looper
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveSubtitlesStatePlacementTest {

    @Test
    fun cyclePanelGoesRightThenLeftThenOffAndRestartsOnTheRight() {
        val events = mutableListOf<Pair<Boolean, Boolean>>()
        val player = TestPlayer()
        try {
            val state = state(player) { open, onLeft -> events += open to onLeft }

            state.cyclePanel()
            assertTrue(state.isPanelVisible)
            assertFalse(state.isPanelOnLeft)

            state.cyclePanel()
            assertTrue(state.isPanelVisible)
            assertTrue(state.isPanelOnLeft)

            state.cyclePanel()
            assertFalse(state.isPanelVisible)
            assertFalse(state.isPanelOnLeft)

            state.cyclePanel()
            assertTrue(state.isPanelVisible)
            assertFalse(state.isPanelOnLeft)

            assertEquals(
                listOf(
                    true to false,
                    true to true,
                    false to false,
                    true to false,
                ),
                events,
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun closePanelTurnsTheOpenPanelOff() {
        val events = mutableListOf<Pair<Boolean, Boolean>>()
        val player = TestPlayer()
        try {
            val state = state(player) { open, onLeft -> events += open to onLeft }
            state.cyclePanel()
            state.cyclePanel()
            events.clear()

            state.closePanel()

            assertFalse(state.isPanelVisible)
            assertFalse(state.isPanelOnLeft)
            assertEquals(listOf(false to false), events)

            state.cyclePanel()
            assertTrue(state.isPanelVisible)
            assertFalse(state.isPanelOnLeft)
        } finally {
            player.release()
        }
    }

    @Test
    fun closedPanelIgnoresARememberedLeftSide() {
        val player = TestPlayer()
        try {
            val state = state(
                player = player,
                initialPanelVisible = false,
                initialPanelOnLeft = true,
            )

            assertFalse(state.isPanelVisible)
            assertFalse(state.isPanelOnLeft)

            state.cyclePanel()
            assertTrue(state.isPanelVisible)
            assertFalse(state.isPanelOnLeft)
        } finally {
            player.release()
        }
    }

    @Test
    fun openPanelRestoresTheLeftSide() {
        val player = TestPlayer()
        try {
            val state = state(
                player = player,
                initialPanelVisible = true,
                initialPanelOnLeft = true,
            )

            assertTrue(state.isPanelVisible)
            assertTrue(state.isPanelOnLeft)
        } finally {
            player.release()
        }
    }

    private fun state(
        player: TestPlayer,
        initialPanelVisible: Boolean = false,
        initialPanelOnLeft: Boolean = false,
        onPanelPlacementChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    ): LiveSubtitlesState = LiveSubtitlesState(
        player = player,
        context = ApplicationProvider.getApplicationContext(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        initialPanelVisible = initialPanelVisible,
        initialPanelOnLeft = initialPanelOnLeft,
        onPanelPlacementChanged = onPanelPlacementChanged,
    )

    private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private val currentState = State.Builder().build()

        override fun getState(): State = currentState
    }
}
