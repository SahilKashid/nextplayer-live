package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPreferencesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun overlayAndVerticalPositionDefaults() {
        val preferences = PlayerPreferences()

        assertTrue(preferences.showOverlaySubtitlesWithLivePanel)
        assertFalse(preferences.liveSubtitlesPanelOpen)
        assertEquals(PlayerPreferences.DEFAULT_SUBTITLE_VERTICAL_POSITION, preferences.subtitleVerticalPosition)
        assertTrue(preferences.shouldShowOverlaySubtitles(livePanelVisible = false))
        assertTrue(preferences.shouldShowOverlaySubtitles(livePanelVisible = true))
    }

    @Test
    fun overlayHiddenOnlyWhenPanelOpenAndPreferenceOff() {
        val preferences = PlayerPreferences(showOverlaySubtitlesWithLivePanel = false)

        assertTrue(preferences.shouldShowOverlaySubtitles(livePanelVisible = false))
        assertFalse(preferences.shouldShowOverlaySubtitles(livePanelVisible = true))
    }

    @Test
    fun missingKeysUseOverlayAndVerticalDefaults() {
        val decoded = json.decodeFromString<PlayerPreferences>("{}")

        assertTrue(decoded.showOverlaySubtitlesWithLivePanel)
        assertFalse(decoded.liveSubtitlesPanelOpen)
        assertEquals(0f, decoded.subtitleVerticalPosition)
    }

    @Test
    fun serializationRoundTripPreservesOverlayAndVerticalPosition() {
        val original = PlayerPreferences(
            showOverlaySubtitlesWithLivePanel = false,
            subtitleVerticalPosition = 0.25f,
        )

        val decoded = json.decodeFromString<PlayerPreferences>(json.encodeToString(original))

        assertFalse(decoded.showOverlaySubtitlesWithLivePanel)
        assertEquals(0.25f, decoded.subtitleVerticalPosition, 0.0001f)
    }

    @Test
    fun liveSubtitlesPanelOpenRoundTrip() {
        assertFalse(PlayerPreferences().liveSubtitlesPanelOpen)

        val original = PlayerPreferences(liveSubtitlesPanelOpen = true)
        val decoded = json.decodeFromString<PlayerPreferences>(json.encodeToString(original))

        assertTrue(decoded.liveSubtitlesPanelOpen)
    }
}
