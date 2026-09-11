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
import java.io.File
import java.io.IOException

/**
 * Wraps a Matroska/WebM [Extractor] for incomplete local files:
 * - Replaces an unseekable seek map that still has a known duration with
 *   [ApproximateByteSeekMap] (keeps cue-EOF disabled for open, but allows scrubbing).
 * - On [seek], clamps to the readable tip and snaps the byte position back to the nearest
 *   preceding Cluster for demux sync.
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
        val tip = tipProvider()
        val clamped = when {
            tip > 1L -> position.coerceIn(0L, tip - 1L)
            tip == 1L -> 0L
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

    private fun snapToCluster(position: Long, tip: Long): Long {
        if (tip < 4L) return position
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
