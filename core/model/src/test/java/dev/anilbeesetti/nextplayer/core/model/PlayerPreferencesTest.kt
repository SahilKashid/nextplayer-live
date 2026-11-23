package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPreferencesTest {
    @Test
    fun `existing preferences keep tunneled playback disabled`() {
        val preferences = Json.decodeFromString<PlayerPreferences>(
            string = """{"autoplay":false,"preferredAudioLanguage":"en"}""",
        )

        assertFalse(preferences.enableTunneledPlayback)
        assertFalse(preferences.autoplay)
        assertEquals(
            "en",
            preferences.preferredAudioLanguage,
        )
    }

    @Test
    fun `tunneled playback survives serialization`() {
        val preferences = PlayerPreferences(enableTunneledPlayback = true)
        val restored = Json.decodeFromString<PlayerPreferences>(
            Json.encodeToString(value = preferences),
        )

        assertTrue(restored.enableTunneledPlayback)
        assertEquals(
            preferences,
            restored,
        )
    }

    @Test
    fun playerControlAndSubtitleDefaults() {
        val preferences = PlayerPreferences()

        assertTrue(preferences.rememberPlayerBrightness)
        assertTrue(preferences.rememberPlayerZoom)
        assertEquals(
            1f,
            preferences.playerZoom,
            0.0001f,
        )
        assertEquals(
            0f,
            preferences.playerZoomPanX,
            0.0001f,
        )
        assertEquals(
            0f,
            preferences.playerZoomPanY,
            0.0001f,
        )
        assertEquals(
            VideoContentScale.BEST_FIT,
            preferences.playerVideoZoom,
        )
        assertEquals(
            DoubleTapGesture.PLAY_PAUSE,
            preferences.doubleTapGesture,
        )
        assertTrue(preferences.useLongPressControls)
        assertEquals(
            2.0f,
            preferences.longPressControlsSpeed,
            0.0001f,
        )
        assertTrue(preferences.showOverlaySubtitlesWithLivePanel)
        assertEquals(
            PlayerPreferences.DEFAULT_PREFERRED_SUBTITLE_LANGUAGE,
            preferences.preferredSubtitleLanguage,
        )
        assertEquals(
            "eng",
            preferences.preferredSubtitleLanguage,
        )
        assertFalse(preferences.liveSubtitlesPanelOpen)
        assertEquals(
            0.08f,
            PlayerPreferences.MEDIA3_SUBTITLE_BOTTOM_PADDING_FRACTION,
            0.0001f,
        )
        assertEquals(
            0.20f,
            PlayerPreferences.DEFAULT_SUBTITLE_BOTTOM_FRACTION,
            0.0001f,
        )
        assertEquals(
            PlayerPreferences.DEFAULT_SUBTITLE_OFFSET_Y,
            preferences.subtitleOffsetY,
            0.0001f,
        )
        assertEquals(
            -0.12f,
            preferences.subtitleOffsetY,
            0.0001f,
        )
        assertEquals(
            0.20f,
            PlayerPreferences.MEDIA3_SUBTITLE_BOTTOM_PADDING_FRACTION -
                preferences.subtitleOffsetY,
            0.0001f,
        )
        assertTrue(preferences.shouldShowOverlaySubtitles(livePanelVisible = false))
        assertTrue(preferences.shouldShowOverlaySubtitles(livePanelVisible = true))
        assertFalse(preferences.liveSubtitlesPanelOnLeft)
        assertTrue(preferences.showOnVideoSubtitles)
        assertTrue(preferences.shouldShowOnVideoSubtitles())
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
    fun missingKeysUseOverlayAndLiveSubtitleDefaults() {
        val decoded = Json.decodeFromString<PlayerPreferences>("{}")

        assertTrue(decoded.rememberPlayerBrightness)
        assertTrue(decoded.rememberPlayerZoom)
        assertEquals(
            1f,
            decoded.playerZoom,
            0.0001f,
        )
        assertEquals(
            0f,
            decoded.playerZoomPanX,
            0.0001f,
        )
        assertEquals(
            0f,
            decoded.playerZoomPanY,
            0.0001f,
        )
        assertEquals(
            DoubleTapGesture.PLAY_PAUSE,
            decoded.doubleTapGesture,
        )
        assertTrue(decoded.useLongPressControls)
        assertEquals(
            2.0f,
            decoded.longPressControlsSpeed,
            0.0001f,
        )
        assertTrue(decoded.showOverlaySubtitlesWithLivePanel)
        assertEquals(
            "eng",
            decoded.preferredSubtitleLanguage,
        )
        assertFalse(decoded.liveSubtitlesPanelOpen)
        assertFalse(decoded.liveSubtitlesPanelOnLeft)
        assertTrue(decoded.showOnVideoSubtitles)
        assertTrue(decoded.shouldShowOnVideoSubtitles())
        assertEquals(
            PlayerPreferences.DEFAULT_SUBTITLE_OFFSET_Y,
            decoded.subtitleOffsetY,
            0.0001f,
        )
        assertFalse(decoded.enableTunneledPlayback)
    }

    @Test
    fun serializationRoundTripPreservesOverlayPreference() {
        val original = PlayerPreferences(
            showOverlaySubtitlesWithLivePanel = false,
        )

        val decoded = Json.decodeFromString<PlayerPreferences>(Json.encodeToString(original))

        assertFalse(decoded.showOverlaySubtitlesWithLivePanel)
        assertEquals(
            PlayerPreferences.DEFAULT_SUBTITLE_OFFSET_Y,
            decoded.subtitleOffsetY,
            0.0001f,
        )
    }

    @Test
    fun storedJsonFromLegacySliderAtDefaultBecomesTwentyPercentFromBottom() {
        val decoded = PlayerPreferences.decodeStored(
            """{"subtitleVerticalPosition":0.2,"subtitleOffsetY":0.0}""",
        )

        assertEquals(-0.12f, decoded.subtitleOffsetY, 0.0001f)
        assertEquals(
            PlayerPreferences.DEFAULT_SUBTITLE_OFFSET_Y,
            decoded.subtitleOffsetY,
            0.0001f,
        )
        assertFalse("subtitleVerticalPosition" in Json.encodeToString(decoded))
    }

    @Test
    fun storedJsonWithLegacySliderAtBottomStaysAtStockZero() {
        val decoded = PlayerPreferences.decodeStored(
            """{"subtitleVerticalPosition":0.0,"subtitleOffsetY":0.0}""",
        )

        assertEquals(0f, decoded.subtitleOffsetY, 0.0001f)
    }

    @Test
    fun legacySliderAtOrBelowMedia3PaddingMapsToStockZero() {
        assertEquals(
            0f,
            PlayerPreferences.offsetFromLegacySubtitleVerticalPosition(0f),
            0.0001f,
        )
        assertEquals(
            0f,
            PlayerPreferences.offsetFromLegacySubtitleVerticalPosition(0.08f),
            0.0001f,
        )
        assertEquals(
            0f,
            PlayerPreferences.offsetFromLegacySubtitleVerticalPosition(0.05f),
            0.0001f,
        )
        val decoded = PlayerPreferences.decodeStored(
            """{"subtitleVerticalPosition":0.08,"subtitleOffsetY":0.0}""",
        )
        assertEquals(0f, decoded.subtitleOffsetY, 0.0001f)
    }

    @Test
    fun offsetFromLegacySliderSubtractsMedia3Padding() {
        assertEquals(
            -0.12f,
            PlayerPreferences.offsetFromLegacySubtitleVerticalPosition(0.2f),
            0.0001f,
        )
        assertEquals(
            -0.22f,
            PlayerPreferences.offsetFromLegacySubtitleVerticalPosition(0.3f),
            0.0001f,
        )
    }

    @Test
    fun storedJsonKeepsANonZeroStockDragAndDropsTheSliderKey() {
        val decoded = PlayerPreferences.decodeStored(
            """{"subtitleVerticalPosition":0.2,"subtitleOffsetY":-0.1}""",
        )

        assertEquals(-0.1f, decoded.subtitleOffsetY, 0.0001f)
        assertFalse("subtitleVerticalPosition" in Json.encodeToString(decoded))
    }

    @Test
    fun preOffsetInstallsUseTheLegacySliderAsTheOffset() {
        // 1.1.x wrote the slider but not subtitleOffsetY.
        val decoded = PlayerPreferences.decodeStored(
            """{"subtitleVerticalPosition":0.2}""",
        )

        assertEquals(-0.12f, decoded.subtitleOffsetY, 0.0001f)
    }

    @Test
    fun storedJsonWithoutLegacyKeyKeepsAnExplicitZeroOffset() {
        val decoded = PlayerPreferences.decodeStored("""{"subtitleOffsetY":0.0}""")

        assertEquals(0f, decoded.subtitleOffsetY, 0.0001f)
    }

    @Test
    fun liveSubtitlesPanelOpenRoundTrip() {
        assertFalse(PlayerPreferences().liveSubtitlesPanelOpen)

        val original = PlayerPreferences(
            liveSubtitlesPanelOpen = true,
            liveSubtitlesPanelOnLeft = true,
        )
        val decoded = Json.decodeFromString<PlayerPreferences>(Json.encodeToString(original))

        assertTrue(decoded.liveSubtitlesPanelOpen)
        assertTrue(decoded.liveSubtitlesPanelOnLeft)
    }

    @Test
    fun onVideoSubtitlesStayIndependentOfTheLivePanel() {
        val hidden = PlayerPreferences(
            showOnVideoSubtitles = false,
            liveSubtitlesPanelOpen = true,
        )
        val shown = PlayerPreferences(
            showOnVideoSubtitles = true,
            liveSubtitlesPanelOpen = false,
        )

        assertFalse(hidden.shouldShowOnVideoSubtitles())
        assertTrue(shown.shouldShowOnVideoSubtitles())

        val decoded = Json.decodeFromString<PlayerPreferences>(Json.encodeToString(hidden))
        assertFalse(decoded.showOnVideoSubtitles)
        assertTrue(decoded.liveSubtitlesPanelOpen)
    }
}
