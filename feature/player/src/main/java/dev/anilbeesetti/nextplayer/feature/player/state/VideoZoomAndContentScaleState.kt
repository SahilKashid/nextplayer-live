package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.model.VideoContentScale
import dev.anilbeesetti.nextplayer.feature.player.extensions.copy
import dev.anilbeesetti.nextplayer.feature.player.extensions.next
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoZoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun rememberVideoZoomAndContentScaleState(
    player: Player,
    initialContentScale: VideoContentScale,
    enableZoomGesture: Boolean,
    enablePanGesture: Boolean,
    onEvent: (VideoZoomEvent) -> Unit = {},
): VideoZoomAndContentScaleState {
    val coroutineScope = rememberCoroutineScope()
    val videoZoomAndContentScaleState = remember {
        VideoZoomAndContentScaleState(
            player = player,
            initialContentScale = initialContentScale,
            enableZoomGesture = enableZoomGesture,
            enablePanGesture = enablePanGesture,
            onEvent = onEvent,
            coroutineScope = coroutineScope,
        )
    }
    LaunchedEffect(player) { videoZoomAndContentScaleState.observe() }
    return videoZoomAndContentScaleState
}

@Stable
class VideoZoomAndContentScaleState(
    private val player: Player,
    initialContentScale: VideoContentScale,
    private val enableZoomGesture: Boolean = true,
    private val enablePanGesture: Boolean = true,
    private val onEvent: (VideoZoomEvent) -> Unit,
    private val coroutineScope: CoroutineScope,
) {
    companion object Companion {
        private const val CONTENT_SCALE_INDICATOR_DURATION_MS = 1000L
    }

    /**
     * When true, pinch scale and pan come from the global preference, not the
     * per-video [videoZoom] metadata. Resize mode is unchanged.
     */
    var useRememberedZoom: Boolean = false

    private var panX: Float = 0f
    private var panY: Float = 0f
    private var playerAreaWidthPx: Int = 0
    private var playerAreaHeightPx: Int = 0

    var videoContentScale: VideoContentScale by mutableStateOf(initialContentScale)
        private set

    var zoom: Float by mutableFloatStateOf(1f)
        private set

    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    var isZooming: Boolean by mutableStateOf(false)
        private set

    var showContentScaleIndicator: Boolean by mutableStateOf(false)
        private set

    private var showContentScaleJob: Job? = null

    fun onVideoContentScaleChanged(newContentScale: VideoContentScale) {
        videoContentScale = newContentScale
        zoom = 1f
        panX = 0f
        panY = 0f
        offset = Offset.Zero
        onEvent(VideoZoomEvent.ContentScaleChanged(videoContentScale))
        updateVideoScaleMetadataAndSendEvent()
        showContentScaleIndicator()
    }

    /** Apply the global pinch scale and pan. Does not write preferences. */
    fun restoreRememberedZoom(zoom: Float, panX: Float, panY: Float) {
        this.zoom = PlayerZoomPan.clampZoom(zoom)
        this.panX = PlayerZoomPan.clampFraction(panX)
        this.panY = PlayerZoomPan.clampFraction(panY)
        offset = offsetInPlayerArea()
    }

    /**
     * The player slot beside the live-subtitles panel. Pan pixels are derived
     * from the saved fraction so a narrower slot does not keep a full-screen offset.
     */
    fun onPlayerAreaConstraints(widthPx: Int, heightPx: Int) {
        if (widthPx == playerAreaWidthPx && heightPx == playerAreaHeightPx) return
        playerAreaWidthPx = widthPx
        playerAreaHeightPx = heightPx
        if (!isZooming) {
            offset = offsetInPlayerArea()
        }
    }

    private fun showContentScaleIndicator() {
        showContentScaleJob?.cancel()
        showContentScaleIndicator = true
        showContentScaleJob = coroutineScope.launch {
            delay(CONTENT_SCALE_INDICATOR_DURATION_MS)
            showContentScaleIndicator = false
            showContentScaleJob = null
        }
    }

    fun switchToNextVideoContentScale() {
        onVideoContentScaleChanged(videoContentScale.next())
    }

    fun onZoomPanGesture(constraints: Constraints, panChange: Offset, zoomChange: Float) {
        if (player.duration == C.TIME_UNSET) return
        if (!enableZoomGesture) return

        isZooming = true
        playerAreaWidthPx = constraints.maxWidth
        playerAreaHeightPx = constraints.maxHeight
        zoom = PlayerZoomPan.clampZoom(zoom * zoomChange)

        if (enablePanGesture) {
            val maxX = PlayerZoomPan.maxPan(zoom, constraints.maxWidth)
            val maxY = PlayerZoomPan.maxPan(zoom, constraints.maxHeight)
            offset = Offset(
                x = (offset.x + zoom * panChange.x).coerceIn(-maxX, maxX),
                y = (offset.y + zoom * panChange.y).coerceIn(-maxY, maxY),
            )
            panX = PlayerZoomPan.fraction(offset.x, zoom, constraints.maxWidth)
            panY = PlayerZoomPan.fraction(offset.y, zoom, constraints.maxHeight)
        } else {
            offset = offsetInPlayerArea()
        }
    }

    fun onZoomPanGestureEnd() {
        isZooming = false
        updateVideoScaleMetadataAndSendEvent()
    }

    suspend fun observe() {
        if (!useRememberedZoom) {
            zoom = player.currentMediaItem?.mediaMetadata?.videoZoom ?: 1f
        }
        player.listen { events ->
            if (!useRememberedZoom && events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                zoom = player.currentMediaItem?.mediaMetadata?.videoZoom ?: 1f
            }
        }
    }

    private fun offsetInPlayerArea(): Offset = Offset(
        x = PlayerZoomPan.offsetPx(panX, zoom, playerAreaWidthPx),
        y = PlayerZoomPan.offsetPx(panY, zoom, playerAreaHeightPx),
    )

    private fun updateVideoScaleMetadataAndSendEvent(zoom: Float = this.zoom) {
        val currentMediaItem = player.currentMediaItem ?: return
        player.replaceMediaItem(
            player.currentMediaItemIndex,
            currentMediaItem.copy(videoZoom = zoom),
        )
        onEvent(VideoZoomEvent.ZoomChanged(currentMediaItem, zoom, panX, panY))
    }
}

sealed interface VideoZoomEvent {
    data class ContentScaleChanged(val contentScale: VideoContentScale) : VideoZoomEvent
    data class ZoomChanged(
        val mediaItem: MediaItem,
        val zoom: Float,
        val panX: Float = 0f,
        val panY: Float = 0f,
    ) : VideoZoomEvent
}
