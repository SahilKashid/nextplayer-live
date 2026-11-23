package dev.anilbeesetti.nextplayer.feature.player.service

/**
 * Remembers the playback position just before [androidx.media3.common.Player.seekToPrevious]
 * restarts the current item (the position was past `maxSeekToPreviousPosition`, default 3s).
 *
 * Stock then leaves the item at ~0 on a second Previous. When that happens, the SEEK save
 * path should persist this remembered position so Next can resume where the user was.
 *
 * Clear on a normal in-item seek so a scrub is saved as-is.
 */
class PreviousRestartResumeStore {
    private var mediaId: String? = null
    private var positionMs: Long = 0L

    /**
     * Call immediately before [androidx.media3.common.Player.seekToPrevious] runs, while
     * [positionMs] is still the current playback position.
     */
    fun onSeekToPrevious(
        mediaId: String?,
        positionMs: Long,
        maxSeekToPreviousPositionMs: Long,
    ) {
        if (mediaId != null && positionMs > maxSeekToPreviousPositionMs) {
            this.mediaId = mediaId
            this.positionMs = positionMs
        }
        // A press inside the window leaves the item. Keep a memory for this same item so
        // the save path can use the pre-restart position. Drop it if the item changed.
        else if (this.mediaId != mediaId) {
            clear()
        }
    }

    /** A user-chosen seek in the current item; do not reuse a pre-restart position. */
    fun clear() {
        mediaId = null
        positionMs = 0L
    }

    /**
     * Position to persist when leaving [mediaId] via SEEK. If this item was just
     * restarted by Previous, returns the pre-restart position and clears the memory.
     */
    fun consumeLeavePosition(mediaId: String, reportedPositionMs: Long): Long {
        if (this.mediaId == mediaId) {
            val remembered = positionMs
            clear()
            return remembered
        }
        return reportedPositionMs
    }
}
