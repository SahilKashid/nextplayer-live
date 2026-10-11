package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DirectOnVideoSubtitleOutputTest {

    @Test
    fun onCuesPaintsTheSubtitleViewBeforeTheNextMainThreadMessage() {
        val view = SubtitleView(RuntimeEnvironment.getApplication())
        val output = DirectOnVideoSubtitleOutput().apply { this.view = view }

        // Robolectric leaves the main looper paused, so a posted update would still be queued.
        output.onCues(CueGroup(listOf(Cue.Builder().setText("on time").build()), 0L))

        assertEquals(listOf("on time"), shownTexts(view))
    }

    @Suppress("UNCHECKED_CAST")
    private fun shownTexts(view: SubtitleView): List<String> {
        val field = SubtitleView::class.java.getDeclaredField("cues")
        field.isAccessible = true
        val cues = field.get(view) as List<Cue>
        return cues.map { it.text?.toString().orEmpty() }
    }
}
