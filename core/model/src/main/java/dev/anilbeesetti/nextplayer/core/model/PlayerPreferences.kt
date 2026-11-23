package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.floatOrNull

@Serializable
data class PlayerPreferences(
    val resume: Resume = Resume.YES,
    val rememberPlayerBrightness: Boolean = true,
    val playerBrightness: Float = 0.5f,
    /** Global pinch scale and pan, same scope as [playerBrightness]. Resize mode stays [playerVideoZoom]. */
    val rememberPlayerZoom: Boolean = true,
    val playerZoom: Float = 1f,
    val playerZoomPanX: Float = 0f,
    val playerZoomPanY: Float = 0f,
    val minDurationForFastSeek: Long = 120000L,
    val rememberSelections: Boolean = true,
    val playerScreenOrientation: ScreenOrientation = ScreenOrientation.VIDEO_ORIENTATION,
    val playerVideoZoom: VideoContentScale = VideoContentScale.BEST_FIT,
    val defaultPlaybackSpeed: Float = 1.0f,
    val autoplay: Boolean = true,
    val enableTunneledPlayback: Boolean = false,
    val autoPip: Boolean = true,
    val autoBackgroundPlay: Boolean = false,
    val loopMode: LoopMode = LoopMode.OFF,

    // Controls (Gestures)
    @Deprecated(
        message =
            "Use individual enableVolumeSwipeGesture and enableBrightnessSwipeGesture instead",
    )
    val useSwipeControls: Boolean = true,
    val enableVolumeSwipeGesture: Boolean = true,
    val enableBrightnessSwipeGesture: Boolean = true,
    val useSeekControls: Boolean = true,
    val useZoomControls: Boolean = true,
    val enablePanGesture: Boolean = false,
    val doubleTapGesture: DoubleTapGesture = DoubleTapGesture.PLAY_PAUSE,
    val useLongPressControls: Boolean = true,
    val longPressControlsSpeed: Float = 2.0f,
    val seekIncrement: Int = DEFAULT_SEEK_INCREMENT,
    val seekSensitivity: Float = DEFAULT_SEEK_SENSITIVITY,
    val volumeGestureSensitivity: Float = DEFAULT_VOLUME_GESTURE_SENSITIVITY,
    val brightnessGestureSensitivity: Float = DEFAULT_BRIGHTNESS_GESTURE_SENSITIVITY,

    // Player Interface
    val controllerAutoHideTimeout: Int = DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT,
    val controlButtonsPosition: ControlButtonsPosition = ControlButtonsPosition.LEFT,
    val hidePlayerButtonsBackground: Boolean = false,
    val useMaterialYouControls: Boolean = false,
    val showRemainingTime: Boolean = false,

    // Audio Preferences
    val preferredAudioLanguage: String = "",
    val pauseOnHeadsetDisconnect: Boolean = true,
    val requireAudioFocus: Boolean = true,
    val showSystemVolumePanel: Boolean = true,
    val enableVolumeBoost: Boolean = false,

    // Subtitle Preferences
    val useSystemCaptionStyle: Boolean = false,
    val preferredSubtitleLanguage: String = DEFAULT_PREFERRED_SUBTITLE_LANGUAGE,
    val subtitleTextEncoding: String = "",
    val subtitleTextSize: Int = DEFAULT_SUBTITLE_TEXT_SIZE,
    val subtitleBackground: Boolean = false,
    val subtitleFont: Font = Font.DEFAULT,
    val subtitleTextBold: Boolean = true,
    val applyEmbeddedStyles: Boolean = true,
    /**
     * Kept so older preference JSON still decodes. The player no longer uses this to hide
     * the overlay; on-video subtitles follow [showOnVideoSubtitles].
     */
    val showOverlaySubtitlesWithLivePanel: Boolean = true,
    val liveSubtitlesPanelOpen: Boolean = false,
    /** Restored only while [liveSubtitlesPanelOpen] is true. A new cycle always starts on the right. */
    val liveSubtitlesPanelOnLeft: Boolean = false,
    /**
     * On-video subtitle rendering. Independent of the live panel: the player keeps a text
     * track selected so the panel can still list cues when this is false.
     */
    val showOnVideoSubtitles: Boolean = true,
    /**
     * Vertical distance from the default subtitle spot, as a fraction of the player height
     * (stock coordinate system: negative is up). The default cue spot already includes
     * Media3's [MEDIA3_SUBTITLE_BOTTOM_PADDING_FRACTION] (0.08). Live's default offset
     * is [DEFAULT_SUBTITLE_OFFSET_Y] (-0.12) so cues sit about 20% from the bottom.
     * Drag still saves through this same field.
     */
    val subtitleOffsetY: Float = DEFAULT_SUBTITLE_OFFSET_Y,
) {
    /**
     * Legacy helper for [showOverlaySubtitlesWithLivePanel]. Playback uses
     * [shouldShowOnVideoSubtitles], which ignores the live panel.
     */
    fun shouldShowOverlaySubtitles(livePanelVisible: Boolean): Boolean =
        !livePanelVisible || showOverlaySubtitlesWithLivePanel

    /** On-video subtitle view. Ignores whether the live panel is open. */
    fun shouldShowOnVideoSubtitles(): Boolean = showOnVideoSubtitles

    /**
     * Live 1.2.0 and earlier stored a separate slider ([legacySubtitleVerticalPosition]),
     * a desired bottom-padding fraction (default 0.2, 0 = Bottom). When that key is still
     * in stored JSON and the stock drag offset is still 0 (or was never written), convert
     * it with [offsetFromLegacySubtitleVerticalPosition] so the old 0.2 default becomes
     * [DEFAULT_SUBTITLE_OFFSET_Y] (-0.12, about 20% from the bottom after Media3's 0.08
     * padding). A non-zero saved drag is left alone. The leftover key is dropped on the
     * next write.
     */
    fun applyLegacySubtitleVerticalPosition(
        legacySubtitleVerticalPosition: Float?,
        subtitleOffsetYExplicit: Boolean,
    ): PlayerPreferences {
        if (legacySubtitleVerticalPosition == null) return this
        if (subtitleOffsetYExplicit && subtitleOffsetY != 0f) return this
        return copy(
            subtitleOffsetY = offsetFromLegacySubtitleVerticalPosition(
                legacySubtitleVerticalPosition,
            ),
        )
    }

    companion object {
        private val storedJson = Json { ignoreUnknownKeys = true }

        /**
         * Decode DataStore / backup JSON, applying [applyLegacySubtitleVerticalPosition]
         * when the removed Live slider key is present.
         */
        fun decodeStored(text: String, json: Json = storedJson): PlayerPreferences {
            val element = json.parseToJsonElement(text)
            val obj = element as? JsonObject
            val decoded = json.decodeFromJsonElement(serializer(), element)
            return decoded.applyLegacySubtitleVerticalPosition(
                legacySubtitleVerticalPosition =
                    (obj?.get("subtitleVerticalPosition") as? JsonPrimitive)?.floatOrNull,
                subtitleOffsetYExplicit = obj?.containsKey("subtitleOffsetY") == true,
            )
        }

        const val DEFAULT_SEEK_INCREMENT = 10
        const val DEFAULT_SEEK_SENSITIVITY = 0.50f
        const val DEFAULT_VOLUME_GESTURE_SENSITIVITY = 0.50f
        const val DEFAULT_BRIGHTNESS_GESTURE_SENSITIVITY = 0.50f

        /** ISO639-2/T code matching LocalesHelper / Settings subtitle language list. */
        const val DEFAULT_PREFERRED_SUBTITLE_LANGUAGE = "eng"
        const val DEFAULT_SUBTITLE_TEXT_SIZE = 20
        const val DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT = 4

        /**
         * [androidx.media3.ui.SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION] in
         * `androidx.media3:media3-ui:1.11.1` (verified 0.08f). Stock does not call
         * `setBottomPaddingFraction`, so this is the default cue spot: about 8% up
         * from the bottom of the player.
         */
        const val MEDIA3_SUBTITLE_BOTTOM_PADDING_FRACTION = 0.08f

        /** Desired cue position from the bottom of the player, as a height fraction. */
        const val DEFAULT_SUBTITLE_BOTTOM_FRACTION = 0.20f

        /**
         * Live default for stock drag: [MEDIA3_SUBTITLE_BOTTOM_PADDING_FRACTION] minus
         * [DEFAULT_SUBTITLE_BOTTOM_FRACTION], i.e. 0.08 - 0.20 = -0.12. Combined with
         * Media3's unchanged 0.08 padding, cues sit about 20% from the bottom. Same
         * units as stock `subtitleOffsetY`: a fraction of player height, negative = up.
         */
        const val DEFAULT_SUBTITLE_OFFSET_Y =
            MEDIA3_SUBTITLE_BOTTOM_PADDING_FRACTION - DEFAULT_SUBTITLE_BOTTOM_FRACTION

        /**
         * Maps a leftover Live slider value (a bottom-padding fraction) to stock
         * [subtitleOffsetY]. Values at or below [MEDIA3_SUBTITLE_BOTTOM_PADDING_FRACTION]
         * (including Bottom = 0) become stock 0 so cues are not pushed below the default
         * spot. The old default 0.2 becomes [DEFAULT_SUBTITLE_OFFSET_Y] (-0.12).
         */
        fun offsetFromLegacySubtitleVerticalPosition(
            legacySubtitleVerticalPosition: Float,
        ): Float {
            val lift = legacySubtitleVerticalPosition - MEDIA3_SUBTITLE_BOTTOM_PADDING_FRACTION
            if (lift <= 0f) return 0f
            return -lift
        }
    }
}
