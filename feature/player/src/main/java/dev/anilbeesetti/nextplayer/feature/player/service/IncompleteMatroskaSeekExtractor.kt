package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import dev.anilbeesetti.nextplayer.core.media.network.datasource.IncompleteLocalMedia
import dev.anilbeesetti.nextplayer.core.media.network.datasource.ReadableTipTracker
import java.io.File
import java.io.IOException

/**
 * Wraps a Matroska/WebM [Extractor] for incomplete local files:
 * - Replaces an unseekable seek map that still has a known duration with
 *   [ApproximateByteSeekMap] (keeps cue-EOF disabled for open, but allows scrubbing).
 * - On [seek], clamps to the safe readable tip (tip minus safety margin) and snaps the byte
 *   position back to the nearest validated preceding Cluster for demux sync.
 *
 * Real cue seek maps (finished files / cues already present) are passed through unchanged.
 */
@UnstableApi
class IncompleteMatroskaSeekExtractor(
    private val delegate: Extractor,
    private val tipProvider: () -> Long,
    private val declaredProvider: () -> Long = { -1L },
    private val filePathProvider: () -> String? = { null },
) : Extractor {

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        delegate.init(
            object : ForwardingExtractorOutput(output) {
                override fun seekMap(seekMap: SeekMap) {
                    val replacement =
                        if (!seekMap.isSeekable &&
                            seekMap.durationUs != C.TIME_UNSET &&
                            seekMap.durationUs > 0L
                        ) {
                            ApproximateByteSeekMap(
                                durationUs = seekMap.durationUs,
                                tipProvider = tipProvider,
                                declaredProvider = declaredProvider,
                            )
                        } else {
                            seekMap
                        }
                    super.seekMap(replacement)
                }
            },
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        val tip = resolveTip()
        val safe = ApproximateByteSeekMap.safeTip(tip)
        val clampLimit = when {
            safe > 1L -> safe - 1L
            tip > 1L -> tip - 1L
            else -> 0L
        }
        val clamped = when {
            tip > 0L -> position.coerceIn(0L, clampLimit)
            else -> position.coerceAtLeast(0L)
        }
        val snapped = snapToCluster(clamped, tip)
        delegate.seek(snapped, timeUs)
    }

    override fun release() {
        delegate.release()
    }

    override fun getUnderlyingImplementation(): Extractor =
        delegate.underlyingImplementation

    /**
     * Tip from the tracker; if unknown (-1), fall back to [IncompleteLocalMedia.inspect]
     * before seeking so we still clamp/snap against a real readable end.
     */
    private fun resolveTip(): Long {
        val tracked = tipProvider()
        if (tracked > 0L) return tracked
        val path = filePathProvider() ?: return tracked
        return try {
            val snap = IncompleteLocalMedia.inspect(path)
            if (snap.readableEnd > 0L) {
                ReadableTipTracker.update(path, snap.readableEnd, snap.declaredLength)
                ReadableTipTracker.update("file://$path", snap.readableEnd, snap.declaredLength)
                snap.readableEnd
            } else {
                tracked
            }
        } catch (_: Exception) {
            tracked
        }
    }

    private fun snapToCluster(position: Long, tip: Long): Long {
        if (tip < 5L) return position
        val path = filePathProvider() ?: return position
        return try {
            val file = File(path)
            if (!file.exists()) return position
            MatroskaClusterFinder.findNearestClusterStart(file, position, tip)
        } catch (_: IOException) {
            position
        } catch (_: SecurityException) {
            position
        }
    }
}
