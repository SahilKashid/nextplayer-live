package dev.anilbeesetti.nextplayer.feature.player.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviousRestartResumeStoreTest {
    private val store = PreviousRestartResumeStore()
    private val maxSeek = 3_000L

    @Test
    fun restartThenLeaveViaPreviousKeepsPreRestartPosition() {
        store.onSeekToPrevious(
            mediaId = "current",
            positionMs = 120_000L,
            maxSeekToPreviousPositionMs = maxSeek,
        )
        // Second press is inside the window; the player is already at ~0.
        store.onSeekToPrevious(
            mediaId = "current",
            positionMs = 0L,
            maxSeekToPreviousPositionMs = maxSeek,
        )

        assertEquals(
            120_000L,
            store.consumeLeavePosition(
                mediaId = "current",
                reportedPositionMs = 0L,
            ),
        )
    }

    @Test
    fun leavePositionWithoutARestartUsesTheReportedValue() {
        assertEquals(
            0L,
            store.consumeLeavePosition(
                mediaId = "current",
                reportedPositionMs = 0L,
            ),
        )
        assertEquals(
            45_000L,
            store.consumeLeavePosition(
                mediaId = "current",
                reportedPositionMs = 45_000L,
            ),
        )
    }

    @Test
    fun positionAtOrBelowTheWindowIsNotRemembered() {
        store.onSeekToPrevious(
            mediaId = "current",
            positionMs = maxSeek,
            maxSeekToPreviousPositionMs = maxSeek,
        )

        assertEquals(
            0L,
            store.consumeLeavePosition(
                mediaId = "current",
                reportedPositionMs = 0L,
            ),
        )
    }

    @Test
    fun normalSeekClearsTheRememberedPosition() {
        store.onSeekToPrevious(
            mediaId = "current",
            positionMs = 120_000L,
            maxSeekToPreviousPositionMs = maxSeek,
        )
        store.clear()

        assertEquals(
            0L,
            store.consumeLeavePosition(
                mediaId = "current",
                reportedPositionMs = 0L,
            ),
        )
    }

    @Test
    fun rememberedPositionIsNotUsedForADifferentItem() {
        store.onSeekToPrevious(
            mediaId = "current",
            positionMs = 120_000L,
            maxSeekToPreviousPositionMs = maxSeek,
        )

        assertEquals(
            0L,
            store.consumeLeavePosition(
                mediaId = "other",
                reportedPositionMs = 0L,
            ),
        )
        assertEquals(
            120_000L,
            store.consumeLeavePosition(
                mediaId = "current",
                reportedPositionMs = 0L,
            ),
        )
    }

    @Test
    fun consumeClearsSoALaterLeaveUsesTheReportedPosition() {
        store.onSeekToPrevious(
            mediaId = "current",
            positionMs = 120_000L,
            maxSeekToPreviousPositionMs = maxSeek,
        )
        store.consumeLeavePosition(
            mediaId = "current",
            reportedPositionMs = 0L,
        )

        assertEquals(
            1_000L,
            store.consumeLeavePosition(
                mediaId = "current",
                reportedPositionMs = 1_000L,
            ),
        )
    }

    @Test
    fun aLaterRestartOverwritesTheRememberedPosition() {
        store.onSeekToPrevious(
            mediaId = "current",
            positionMs = 120_000L,
            maxSeekToPreviousPositionMs = maxSeek,
        )
        store.onSeekToPrevious(
            mediaId = "current",
            positionMs = 8_000L,
            maxSeekToPreviousPositionMs = maxSeek,
        )

        assertEquals(
            8_000L,
            store.consumeLeavePosition(
                mediaId = "current",
                reportedPositionMs = 0L,
            ),
        )
    }
}
