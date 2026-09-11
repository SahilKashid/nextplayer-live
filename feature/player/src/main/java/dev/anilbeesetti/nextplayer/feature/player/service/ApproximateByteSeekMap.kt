package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import kotlin.math.min

/**
 * Estimated byte-position [SeekMap] for incomplete Matroska/WebM where cue-EOF seek is disabled.
 *
 * Maps timeline position across the **full** [durationUs] (Info duration when known — same as VLC
 * showing full length) onto bytes, preferring declared-length mapping clamped to the current
 * readable tip so scrubbing toward the end of a partially downloaded file lands near the tip.
 *
 * When declared length is unknown, falls back to tip-relative mapping.
 */
@UnstableApi
class ApproximateByteSeekMap(
    private val durationUs: Long,
    private val tipProvider: () -> Long,
    private val declaredProvider: () -> Long = { -1L },
) : SeekMap {

    override fun isSeekable(): Boolean =
        durationUs != C.TIME_UNSET && tipProvider() > 0L

    override fun getDurationUs(): Long = durationUs

    override fun isEstimated(): Boolean = true

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val tip = tipProvider().coerceAtLeast(1L)
        val declared = declaredProvider()
        val clampedTime = when {
            durationUs <= 0L || durationUs == C.TIME_UNSET -> 0L
            timeUs < 0L -> 0L
            timeUs > durationUs -> durationUs
            else -> timeUs
        }
        val mapped = if (declared > 0L && durationUs > 0L && durationUs != C.TIME_UNSET) {
            // Declared-length mapping, then clamp into [0, tip).
            val pos = (clampedTime * declared) / durationUs
            min(pos, tip - 1L)
        } else if (durationUs > 0L && durationUs != C.TIME_UNSET) {
            (clampedTime * tip) / durationUs
        } else {
            0L
        }
        val position = mapped.coerceIn(0L, tip - 1L)
        val point = SeekPoint(clampedTime, position)
        return SeekMap.SeekPoints(point)
    }
}
