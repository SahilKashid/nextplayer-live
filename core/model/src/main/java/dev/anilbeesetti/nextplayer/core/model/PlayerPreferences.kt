package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

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
    @Deprecated(message = "Use individual enableVolumeSwipeGesture and enableBrightnessSwipeGesture instead")
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
    val subtitleVerticalPosition: Float = DEFAULT_SUBTITLE_VERTICAL_POSITION,
) {
    /**
     * Legacy helper for [showOverlaySubtitlesWithLivePanel]. Playback uses
     * [shouldShowOnVideoSubtitles], which ignores the live panel.
     */
    fun shouldShowOverlaySubtitles(livePanelVisible: Boolean): Boolean =
        !livePanelVisible || showOverlaySubtitlesWithLivePanel

    /** On-video subtitle view. Ignores whether the live panel is open. */
    fun shouldShowOnVideoSubtitles(): Boolean = showOnVideoSubtitles

    companion object {
        const val DEFAULT_SEEK_INCREMENT = 10
        const val DEFAULT_SEEK_SENSITIVITY = 0.50f
        const val DEFAULT_VOLUME_GESTURE_SENSITIVITY = 0.50f
        const val DEFAULT_BRIGHTNESS_GESTURE_SENSITIVITY = 0.50f

        /** ISO639-2/T code matching LocalesHelper / Settings subtitle language list. */
        const val DEFAULT_PREFERRED_SUBTITLE_LANGUAGE = "eng"
        const val DEFAULT_SUBTITLE_TEXT_SIZE = 20
        const val DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT = 4
        const val MIN_SUBTITLE_VERTICAL_POSITION = 0f
        const val DEFAULT_SUBTITLE_VERTICAL_POSITION = 0.2f
        const val MAX_SUBTITLE_VERTICAL_POSITION = 0.5f
    }
}
