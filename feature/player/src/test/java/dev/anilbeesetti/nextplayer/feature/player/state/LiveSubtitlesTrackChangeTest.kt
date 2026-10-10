package dev.anilbeesetti.nextplayer.feature.player.state

import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSubtitlesTrackChangeTest {

    private val cues = listOf(
        TimedCue(startMs = 1_000L, endMs = 2_000L, text = "a"),
        TimedCue(startMs = 3_000L, endMs = 4_000L, text = "b"),
        TimedCue(startMs = 5_000L, endMs = 6_000L, text = "c"),
    )

    @Test
    fun firstSignature_shouldReset() {
        assertTrue(LiveSubtitlesTrackChange.shouldResetForSignature(null, "mediaA|uriA|track1"))
    }

    @Test
    fun sameSignature_shouldNotReset() {
        val sig = "mediaA|uriA|track1"
        assertFalse(LiveSubtitlesTrackChange.shouldResetForSignature(sig, sig))
    }

    @Test
    fun differentMedia_shouldReset() {
        assertTrue(
            LiveSubtitlesTrackChange.shouldResetForSignature(
                "mediaA|uriA|track1",
                "mediaB|uriB|track1",
            ),
        )
    }

    @Test
    fun resetSnapshot_clearsIdentitiesAndReenablesFollow() {
        val snap = LiveSubtitlesTrackChange.resetSnapshotForSignature("mediaB|uriB|eng")
        assertEquals("mediaB|uriB|eng", snap.listResetKey)
        assertTrue(snap.isLoading)
        assertTrue(snap.isFollowing)
        assertTrue(snap.cuesEmpty)
        assertEquals(-1, snap.currentCueIndex)
        assertEquals(-1, snap.scrollTargetIndex)
        assertNull(snap.highlightedCueKey)
        assertNull(snap.scrollTargetKey)
        assertFalse(snap.isUnsupportedTrack)
    }

    @Test
    fun resetSnapshot_noneSignature_notLoading() {
        val snap = LiveSubtitlesTrackChange.resetSnapshotForSignature("none")
        assertEquals("none", snap.listResetKey)
        assertFalse(snap.isLoading)
        assertTrue(snap.isFollowing)
    }

    @Test
    fun nearestCue_beforeFirst_picksFirstUpcoming() {
        assertEquals(
            0,
            LiveSubtitlesTrackChange.nearestCueIndexByPlayhead(cues, 500L),
        )
    }

    @Test
    fun nearestCue_insideCue_picksContaining() {
        assertEquals(
            1,
            LiveSubtitlesTrackChange.nearestCueIndexByPlayhead(cues, 3_500L),
        )
    }

    @Test
    fun nearestCue_betweenCues_picksLastStarted() {
        // Gap after cue 0 ends (2000) before cue 1 starts (3000)
        assertEquals(
            0,
            LiveSubtitlesTrackChange.nearestCueIndexByPlayhead(cues, 2_500L),
        )
    }

    @Test
    fun nearestCue_afterLast_picksLastStarted() {
        assertEquals(
            2,
            LiveSubtitlesTrackChange.nearestCueIndexByPlayhead(cues, 9_000L),
        )
    }

    @Test
    fun nearestCue_emptyList_returnsMinusOne() {
        assertEquals(
            -1,
            LiveSubtitlesTrackChange.nearestCueIndexByPlayhead(emptyList(), 1_000L),
        )
    }

    @Test
    fun nearestCue_atCueStart_picksContaining() {
        assertEquals(
            0,
            LiveSubtitlesTrackChange.nearestCueIndexByPlayhead(cues, 1_000L),
        )
    }
}
