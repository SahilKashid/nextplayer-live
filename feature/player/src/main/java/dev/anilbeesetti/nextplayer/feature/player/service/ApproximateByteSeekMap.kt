package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import kotlin.math.min

/**
 * Estimated byte-position [SeekMap] for incomplete Matroska/WebM where cue-EOF seek is disabled.
 *
 * Maps timeline position across the full [durationUs] (Info duration when known — same as VLC
 * showing full length) onto the **current readable tip** (tip-relative), with a safety margin so
 * scrubbing never lands in the unfinished edge of a growing / zero-preallocated file.
 *
 * Declared-length mapping is intentionally not used: when declared ≫ tip, that approach clamps
 * almost any mid-timeline scrub to tip−1 (truncated cluster / zero padding), which then fails
 * Media3 parse (`No valid varint length mask`).
 *
 * [declaredProvider] is retained for callers / diagnostics but does not affect byte mapping.
 */
@UnstableApi
class ApproximateByteSeekMap(
    private val durationUs: Long,
    private val tipProvider: () -> Long,
    @Suppress("unused") private val declaredProvider: () -> Long = { -1L },
) : SeekMap {

    override fun isSeekable(): Boolean =
        durationUs != C.TIME_UNSET && tipProvider() > 0L

    override fun getDurationUs(): Long = durationUs

    override fun isEstimated(): Boolean = true

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val tip = tipProvider().coerceAtLeast(0L)
        val safe = safeTip(tip).coerceAtLeast(1L)
        val clampedTime = when {
            durationUs <= 0L || durationUs == C.TIME_UNSET -> 0L
            timeUs < 0L -> 0L
            timeUs > durationUs -> durationUs
            else -> timeUs
        }
        val mapped = if (durationUs > 0L && durationUs != C.TIME_UNSET) {
            (clampedTime * safe) / durationUs
        } else {
            0L
        }
        val position = mapped.coerceIn(0L, safe - 1L)
        val point = SeekPoint(clampedTime, position)
        return SeekMap.SeekPoints(point)
    }

    companion object {
        /** Prefer not seeking into the last ~1 MiB of a growing tip (truncated cluster). */
        const val SAFETY_MARGIN_BYTES: Long = 1024L * 1024L

        /** `min(1MiB, tip/8)` — scales down for small tips. */
        fun safetyMarginFor(tip: Long): Long {
            if (tip <= 0L) return 0L
            return min(SAFETY_MARGIN_BYTES, tip / 8L)
        }

        /** Readable tip minus [safetyMarginFor]; never negative. */
        fun safeTip(tip: Long): Long {
            if (tip <= 0L) return 0L
            return (tip - safetyMarginFor(tip)).coerceAtLeast(0L)
        }
    }
}
