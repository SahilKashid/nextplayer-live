package dev.anilbeesetti.nextplayer.feature.player.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import dev.anilbeesetti.nextplayer.feature.player.utils.subtitle.SubtitleCueLoader
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val AutoFollowResumeDelay = 3.seconds

@UnstableApi
@Composable
fun rememberLiveSubtitlesState(
    player: Player,
    subtitleDelayMs: Long = 0L,
): LiveSubtitlesState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(player) { LiveSubtitlesState(player, context, scope) }
    LaunchedEffect(player) { state.observe() }
    LaunchedEffect(subtitleDelayMs) {
        state.subtitleDelayMs = subtitleDelayMs
        state.updateCurrentCueIndex()
    }
    return state
}

@Stable
@UnstableApi
class LiveSubtitlesState(
    private val player: Player,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var cues: List<TimedCue> by mutableStateOf(emptyList())
        private set

    var currentCueIndex: Int by mutableIntStateOf(-1)
        private set

    var isPanelVisible: Boolean by mutableStateOf(false)
        private set

    var isFollowing: Boolean by mutableStateOf(true)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    var isUnsupportedTrack: Boolean by mutableStateOf(false)
        private set

    var subtitleDelayMs: Long = 0L

    private var loadJob: Job? = null
    private var resumeFollowJob: Job? = null
    private var lastTrackSignature: String? = null

    fun togglePanel() {
        isPanelVisible = !isPanelVisible
        if (isPanelVisible) {
            isFollowing = true
            resumeFollowJob?.cancel()
        }
    }

    fun updatePanelVisible(visible: Boolean) {
        isPanelVisible = visible
        if (visible) {
            isFollowing = true
            resumeFollowJob?.cancel()
        }
    }

    fun onUserScroll() {
        isFollowing = false
        resumeFollowJob?.cancel()
        resumeFollowJob = scope.launch {
            delay(AutoFollowResumeDelay)
            isFollowing = true
        }
    }

    fun jumpToCurrent() {
        resumeFollowJob?.cancel()
        isFollowing = true
    }

    fun seekToCue(cue: TimedCue) {
        player.seekTo(cue.startMs.coerceAtLeast(0L))
        jumpToCurrent()
    }

    suspend fun observe() {
        reloadIfNeeded(force = true)
        updateCurrentCueIndex()

        player.listen { events ->
            if (events.containsAny(
                    Player.EVENT_TRACKS_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                reloadIfNeeded(force = false)
            }
            if (events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                )
            ) {
                updateCurrentCueIndex()
            }
        }
    }

    fun updateCurrentCueIndex(positionMs: Long = player.currentPosition) {
        if (cues.isEmpty()) {
            currentCueIndex = -1
            return
        }
        val effective = positionMs - subtitleDelayMs
        val index = cues.indexOfLast { cue ->
            effective >= cue.startMs && effective < cue.endMs
        }.takeIf { it >= 0 } ?: cues.indexOfLast { cue -> effective >= cue.startMs }
        currentCueIndex = index
    }

    private fun reloadIfNeeded(force: Boolean) {
        val signature = trackSignature()
        if (!force && signature == lastTrackSignature) return
        lastTrackSignature = signature
        loadJob?.cancel()
        loadJob = scope.launch {
            isLoading = true
            val loaded = SubtitleCueLoader.loadSelectedTrackCues(context, player)
            cues = loaded
            isUnsupportedTrack = signature != "none" && loaded.isEmpty()
            isLoading = false
            updateCurrentCueIndex()
        }
    }

    private fun trackSignature(): String {
        val selected = player.currentTracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_TEXT && it.isSelected
        } ?: return "none"
        val format = selected.getTrackFormat(0)
        return listOf(
            player.currentMediaItem?.mediaId.orEmpty(),
            format.id.orEmpty(),
            format.label.orEmpty(),
            format.sampleMimeType.orEmpty(),
        ).joinToString("|")
    }
}
