package dev.anilbeesetti.nextplayer.feature.player.state

import android.os.Looper
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveSubtitlesHighlightTimingTest {

    @Test
    fun boldCueStaysOnTheActiveLineWhileScrollLeadsTheNextOne() {
        val player = TestPlayer()
        try {
            val state = LiveSubtitlesState(
                player = player,
                context = RuntimeEnvironment.getApplication(),
                scope = CoroutineScope(Dispatchers.Unconfined),
            )
            val active = TimedCue(startMs = 0L, endMs = 2_000L, text = "now")
            val upcoming = TimedCue(startMs = 2_000L, endMs = 4_000L, text = "next")
            setCues(state, listOf(active, upcoming))

            // 300ms before the next cue: inside the 380ms scroll lead, still inside "now".
            state.updateCurrentCueIndexFromPosition(1_700L)

            assertEquals("0|2000|now", state.highlightedCueKey)
            assertEquals("2000|4000|next", state.scrollTargetKey)
        } finally {
            player.release()
        }
    }

    private fun setCues(state: LiveSubtitlesState, cues: List<TimedCue>) {
        val setter = LiveSubtitlesState::class.java.declaredMethods
            .first { it.name == "setCues" }
        setter.isAccessible = true
        setter.invoke(state, cues)
    }

    private class TestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private val currentState = State.Builder().build()

        override fun getState(): State = currentState
    }
}
