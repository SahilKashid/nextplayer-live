package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPreferencesTest {
    @Test
    fun `existing preferences keep tunneled playback disabled`() {
        val preferences = Json.decodeFromString<PlayerPreferences>("""{"autoplay":false,"preferredAudioLanguage":"en"}""")

        assertFalse(preferences.enableTunneledPlayback)
        assertFalse(preferences.autoplay)
        assertEquals("en", preferences.preferredAudioLanguage)
    }

    @Test
    fun `tunneled playback survives serialization`() {
        val preferences = PlayerPreferences(enableTunneledPlayback = true)
        val restored = Json.decodeFromString<PlayerPreferences>(Json.encodeToString(preferences))

        assertTrue(restored.enableTunneledPlayback)
        assertEquals(preferences, restored)
    }

    @Test
    fun playerControlAndSubtitleDefaults() {
        val preferences = PlayerPreferences()

        assertTrue(preferences.rememberPlayerBrightness)
        assertEquals(DoubleTapGesture.PLAY_PAUSE, preferences.doubleTapGesture)
        assertTrue(preferences.useLongPressControls)
        assertEquals(2.0f, preferences.longPressControlsSpeed, 0.0001f)
        assertTrue(preferences.showOverlaySubtitlesWithLivePanel)
        assertEquals(PlayerPreferences.DEFAULT_PREFERRED_SUBTITLE_LANGUAGE, preferences.preferredSubtitleLanguage)
        assertEquals("eng", preferences.preferredSubtitleLanguage)
        assertFalse(preferences.liveSubtitlesPanelOpen)
        assertEquals(0f, PlayerPreferences.MIN_SUBTITLE_VERTICAL_POSITION, 0.0001f)
        assertEquals(0.2f, PlayerPreferences.DEFAULT_SUBTITLE_VERTICAL_POSITION, 0.0001f)
        assertEquals(0.5f, PlayerPreferences.MAX_SUBTITLE_VERTICAL_POSITION, 0.0001f)
        assertEquals(PlayerPreferences.DEFAULT_SUBTITLE_VERTICAL_POSITION, preferences.subtitleVerticalPosition)
        assertTrue(preferences.shouldShowOverlaySubtitles(livePanelVisible = false))
        assertTrue(preferences.shouldShowOverlaySubtitles(livePanelVisible = true))
        assertFalse(preferences.enableTunneledPlayback)
        assertFalse(preferences.showRemainingTime)
    }

    @Test
    fun overlayHiddenOnlyWhenPanelOpenAndPreferenceOff() {
        val preferences = PlayerPreferences(showOverlaySubtitlesWithLivePanel = false)

        assertTrue(preferences.shouldShowOverlaySubtitles(livePanelVisible = false))
        assertFalse(preferences.shouldShowOverlaySubtitles(livePanelVisible = true))
    }

    @Test
    fun missingKeysUseOverlayAndVerticalDefaults() {
        val decoded = Json.decodeFromString<PlayerPreferences>("{}")

        assertTrue(decoded.rememberPlayerBrightness)
        assertEquals(DoubleTapGesture.PLAY_PAUSE, decoded.doubleTapGesture)
        assertTrue(decoded.useLongPressControls)
        assertEquals(2.0f, decoded.longPressControlsSpeed, 0.0001f)
        assertTrue(decoded.showOverlaySubtitlesWithLivePanel)
        assertEquals("eng", decoded.preferredSubtitleLanguage)
        assertFalse(decoded.liveSubtitlesPanelOpen)
        assertEquals(0.2f, decoded.subtitleVerticalPosition)
        assertFalse(decoded.enableTunneledPlayback)
    }

    @Test
    fun serializationRoundTripPreservesOverlayAndVerticalPosition() {
        val original = PlayerPreferences(
            showOverlaySubtitlesWithLivePanel = false,
            subtitleVerticalPosition = 0.25f,
        )

        val decoded = Json.decodeFromString<PlayerPreferences>(Json.encodeToString(original))

        assertFalse(decoded.showOverlaySubtitlesWithLivePanel)
        assertEquals(0.25f, decoded.subtitleVerticalPosition, 0.0001f)
    }

    @Test
    fun liveSubtitlesPanelOpenRoundTrip() {
        assertFalse(PlayerPreferences().liveSubtitlesPanelOpen)

        val original = PlayerPreferences(liveSubtitlesPanelOpen = true)
        val decoded = Json.decodeFromString<PlayerPreferences>(Json.encodeToString(original))

        assertTrue(decoded.liveSubtitlesPanelOpen)
    }
}
