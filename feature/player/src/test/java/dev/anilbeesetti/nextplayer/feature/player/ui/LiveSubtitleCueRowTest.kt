package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveSubtitleCueRowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun highlightDoesNotChangeCueLineBreaks() {
        val cue = TimedCue(
            startMs = 1_000L,
            endMs = 4_000L,
            text = "one two three four five six seven eight nine ten",
        )
        // Same text width the 35% left and right docks give the cue column.
        val rowWidth = (800.dp * LiveSubtitlesPanelFraction) - 16.dp

        composeRule.setContent {
            MaterialTheme {
                Column {
                    Column(modifier = Modifier.width(rowWidth).testTag(PlainTag)) {
                        LiveSubtitleCueRow(cue = cue, isHighlighted = false, onClick = {})
                    }
                    Column(modifier = Modifier.width(rowWidth).testTag(HighlightedTag)) {
                        LiveSubtitleCueRow(cue = cue, isHighlighted = true, onClick = {})
                    }
                }
            }
        }

        val plain = layoutOf(PlainTag, cue.text)
        val highlighted = layoutOf(HighlightedTag, cue.text)
        val plainStyle = plain.layoutInput.style
        val highlightedStyle = highlighted.layoutInput.style

        assertEquals(plainStyle.fontWeight, highlightedStyle.fontWeight)
        assertEquals(plainStyle.fontSize, highlightedStyle.fontSize)
        assertEquals(plainStyle.letterSpacing, highlightedStyle.letterSpacing)
        assertEquals(plainStyle.lineHeight, highlightedStyle.lineHeight)
        assertNotEquals(FontWeight.SemiBold, highlightedStyle.fontWeight)
        assertNotEquals(FontWeight.Bold, highlightedStyle.fontWeight)
        assertEquals(plain.size.width, highlighted.size.width)
        assertEquals(plain.lineCount, highlighted.lineCount)
        for (line in 0 until plain.lineCount) {
            assertEquals(plain.getLineStart(line), highlighted.getLineStart(line))
            assertEquals(plain.getLineEnd(line, visibleEnd = true), highlighted.getLineEnd(line, visibleEnd = true))
        }
    }

    private fun layoutOf(tag: String, text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNode(
            hasText(text).and(hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private companion object {
        const val PlainTag = "plain_cue"
        const val HighlightedTag = "highlighted_cue"
    }
}
