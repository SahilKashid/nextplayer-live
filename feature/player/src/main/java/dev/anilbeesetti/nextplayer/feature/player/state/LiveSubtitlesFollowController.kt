package dev.anilbeesetti.nextplayer.feature.player.state

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Delay before auto-resuming follow after a user scroll while playback is active. */
val LiveSubtitlesAutoFollowResumeDelay: Duration = 3.seconds

/**
 * Follow / auto-scroll policy for the live subtitles panel.
 *
 * - User scroll unfollows immediately.
 * - While **playing**, follow resumes after [autoResumeDelay] (unless cancelled).
 * - While **paused**, never auto-resume; stay where the user scrolled.
 * - Pending resume is cancelled on pause.
 * - When playback **starts** again, follow resumes immediately (YouTube-like catch-up).
 * - [jumpToCurrent] always re-enables follow, including while paused.
 */
class LiveSubtitlesFollowController(
    private val scope: CoroutineScope,
    private val isPlaying: () -> Boolean,
    private val autoResumeDelay: Duration = LiveSubtitlesAutoFollowResumeDelay,
    private val onFollowingChanged: (Boolean) -> Unit = {},
) {
    var isFollowing: Boolean = true
        private set(value) {
            if (field == value) return
            field = value
            onFollowingChanged(value)
        }

    private var resumeFollowJob: Job? = null

    fun onUserScroll() {
        isFollowing = false
        resumeFollowJob?.cancel()
        resumeFollowJob = null
        if (!isPlaying()) {
            // Paused: stay unfollowed until jump-to-current or play starts.
            return
        }
        resumeFollowJob = scope.launch {
            delay(autoResumeDelay)
            if (isPlaying()) {
                isFollowing = true
            }
        }
    }

    fun onIsPlayingChanged(playing: Boolean) {
        if (!playing) {
            resumeFollowJob?.cancel()
            resumeFollowJob = null
            return
        }
        // Playback started: catch up immediately.
        resumeFollowJob?.cancel()
        resumeFollowJob = null
        isFollowing = true
    }

    fun jumpToCurrent() {
        resumeFollowJob?.cancel()
        resumeFollowJob = null
        isFollowing = true
    }

    /** Re-enable follow when opening the panel. */
    fun resetFollowing() {
        resumeFollowJob?.cancel()
        resumeFollowJob = null
        isFollowing = true
    }
}
