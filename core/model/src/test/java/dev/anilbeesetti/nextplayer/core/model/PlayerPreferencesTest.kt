package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPreferencesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun playerControlAndSubtitleDefaults() {
        val preferences = PlayerPreferences()

        assertTrue(preferences.rememberPlayerBrightness)
        assertEquals(DoubleTapGesture.PLAY_PAUSE, preferences.doubleTapGesture)
        assertTrue(preferences.useLongPressControls)
        assertEquals(2.0f, preferences.longPressControlsSpeed, 0.0001f)
        assertTrue(preferences.showOverlaySubtitlesWithLivePanel)
        assertFalse(preferences.liveSubtitlesPanelOpen)
        assertEquals(0f, PlayerPreferences.MIN_SUBTITLE_VERTICAL_POSITION, 0.0001f)
        assertEquals(0.2f, PlayerPreferences.DEFAULT_SUBTITLE_VERTICAL_POSITION, 0.0001f)
        assertEquals(0.5f, PlayerPreferences.MAX_SUBTITLE_VERTICAL_POSITION, 0.0001f)
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

        assertTrue(decoded.rememberPlayerBrightness)
        assertEquals(DoubleTapGesture.PLAY_PAUSE, decoded.doubleTapGesture)
        assertTrue(decoded.useLongPressControls)
        assertEquals(2.0f, decoded.longPressControlsSpeed, 0.0001f)
        assertTrue(decoded.showOverlaySubtitlesWithLivePanel)
        assertFalse(decoded.liveSubtitlesPanelOpen)
        assertEquals(0.2f, decoded.subtitleVerticalPosition)
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
