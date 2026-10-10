package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoSelectionTest {

    @Test
    fun noTracksReturnsNull_disableOk() {
        assertNull(
            SubtitleAutoSelection.resolveTrackIndex(
                savedIndex = null,
                trackCount = 0,
                trackLanguages = emptyList(),
                preferredLanguage = "eng",
            ),
        )
    }

    @Test
    fun nullSavedAutoSelectsPreferredEnglish() {
        val index = SubtitleAutoSelection.resolveTrackIndex(
            savedIndex = null,
            trackCount = 3,
            trackLanguages = listOf("spa", "eng", "fra"),
            preferredLanguage = "eng",
        )
        assertEquals(1, index)
    }

    @Test
    fun preferredEnMatchesEngTrack() {
        val index = SubtitleAutoSelection.resolveTrackIndex(
            savedIndex = null,
            trackCount = 2,
            trackLanguages = listOf("spa", "en"),
            preferredLanguage = "eng",
        )
        assertEquals(1, index)
    }

    @Test
    fun unknownLanguageStillSelectsFirst_neverDisable() {
        val index = SubtitleAutoSelection.resolveTrackIndex(
            savedIndex = null,
            trackCount = 2,
            trackLanguages = listOf(null, null),
            preferredLanguage = "eng",
        )
        assertEquals(0, index)
    }

    @Test
    fun savedDisableWithTracksAutoSelects_neverLeaveDisable() {
        val index = SubtitleAutoSelection.resolveTrackIndex(
            savedIndex = -1,
            trackCount = 2,
            trackLanguages = listOf("fra", "eng"),
            preferredLanguage = "eng",
        )
        assertEquals(1, index)
    }

    @Test
    fun savedValidIndexHonored() {
        val index = SubtitleAutoSelection.resolveTrackIndex(
            savedIndex = 0,
            trackCount = 2,
            trackLanguages = listOf("fra", "eng"),
            preferredLanguage = "eng",
        )
        assertEquals(0, index)
    }

    @Test
    fun outOfRangeSavedFallsBackToAutoSelect() {
        val index = SubtitleAutoSelection.resolveTrackIndex(
            savedIndex = 99,
            trackCount = 2,
            trackLanguages = listOf(null, "en"),
            preferredLanguage = "eng",
        )
        assertEquals(1, index)
    }

    @Test
    fun languagesMatchAliases() {
        assertTrue(SubtitleAutoSelection.languagesMatch("en", "eng"))
        assertTrue(SubtitleAutoSelection.languagesMatch("eng", "en"))
        assertTrue(SubtitleAutoSelection.languagesMatch("en", "en-US"))
    }
}
