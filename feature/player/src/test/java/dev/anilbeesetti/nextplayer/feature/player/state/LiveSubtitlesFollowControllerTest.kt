package dev.anilbeesetti.nextplayer.feature.player.state

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSubtitlesFollowControllerTest {

    private val autoResumeDelay = 3.seconds

    @Test
    fun scrollWhilePaused_staysUnfollowedAfterDelay() = runTest {
        var playing = false
        val controller = controller(isPlaying = { playing })

        controller.onUserScroll()
        assertFalse(controller.isFollowing)

        advanceTimeBy(autoResumeDelay.inWholeMilliseconds + 1_000)
        runCurrent()

        assertFalse(controller.isFollowing)
    }

    @Test
    fun scrollWhilePlaying_resumesAfterDelay() = runTest {
        var playing = true
        val controller = controller(isPlaying = { playing })

        controller.onUserScroll()
        assertFalse(controller.isFollowing)

        advanceTimeBy(autoResumeDelay.inWholeMilliseconds - 1)
        runCurrent()
        assertFalse(controller.isFollowing)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(controller.isFollowing)
    }

    @Test
    fun playAfterPausedScroll_followingAgainImmediately() = runTest {
        var playing = false
        val controller = controller(isPlaying = { playing })

        controller.onUserScroll()
        assertFalse(controller.isFollowing)

        advanceTimeBy(autoResumeDelay.inWholeMilliseconds + 500)
        runCurrent()
        assertFalse(controller.isFollowing)

        playing = true
        controller.onIsPlayingChanged(true)
        assertTrue(controller.isFollowing)
    }

    @Test
    fun pauseCancelsPendingResumeFollow() = runTest {
        var playing = true
        val controller = controller(isPlaying = { playing })

        controller.onUserScroll()
        assertFalse(controller.isFollowing)

        playing = false
        controller.onIsPlayingChanged(false)

        advanceTimeBy(autoResumeDelay.inWholeMilliseconds + 1_000)
        runCurrent()
        assertFalse(controller.isFollowing)

        playing = true
        controller.onIsPlayingChanged(true)
        assertTrue(controller.isFollowing)
    }

    @Test
    fun jumpToCurrentWorksWhilePaused() = runTest {
        var playing = false
        val controller = controller(isPlaying = { playing })

        controller.onUserScroll()
        assertFalse(controller.isFollowing)

        controller.jumpToCurrent()
        assertTrue(controller.isFollowing)
    }

    private fun TestScope.controller(
        isPlaying: () -> Boolean,
    ): LiveSubtitlesFollowController =
        LiveSubtitlesFollowController(
            scope = this,
            isPlaying = isPlaying,
            autoResumeDelay = autoResumeDelay,
        )
}
