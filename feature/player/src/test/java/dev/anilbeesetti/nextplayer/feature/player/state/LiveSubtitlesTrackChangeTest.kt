package dev.anilbeesetti.nextplayer.feature.player.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSubtitlesTrackChangeTest {

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
}
