package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import dev.anilbeesetti.nextplayer.core.media.network.datasource.IncompleteLocalMedia
import dev.anilbeesetti.nextplayer.core.media.network.datasource.ReadableTipTracker
import java.io.File

/**
 * Wraps a Matroska/WebM [Extractor] for incomplete local files:
 * - Replaces an unseekable seek map that still has a known duration with an
 *   EBML-validated [IndexSeekMap] of Cluster start positions (cue-EOF stays disabled).
 * - Falls back to the original [SeekMap.Unseekable] when fewer than two Clusters can
 *   be validated — never an approximate byte map.
 * - On [seek], clamps to the safe readable tip and snaps to the nearest indexed
 *   Cluster at or before the target.
 *
 * Real cue seek maps (finished files / cues already present) are passed through unchanged.
 */
@UnstableApi
class IncompleteMatroskaSeekExtractor(
    private val delegate: Extractor,
    private val tipProvider: () -> Long,
    @Suppress("unused") private val declaredProvider: () -> Long = { -1L },
    private val filePathProvider: () -> String? = { null },
) : Extractor {

    private var appOutput: ExtractorOutput? = null
    private var indexedPositions: LongArray? = null
    private var indexedTimesUs: LongArray? = null
    private var lastIndexedTip: Long = -1L
    private var lastDurationUs: Long = C.TIME_UNSET

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        appOutput = output
        delegate.init(
            object : ForwardingExtractorOutput(output) {
                override fun seekMap(seekMap: SeekMap) {
                    super.seekMap(replaceSeekMap(seekMap))
                }
            },
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        val tip = resolveTip()
        maybeRebuildIndex(tip, emit = true)
        val snapped = snapAndClamp(position, tip)
        delegate.seek(snapped, timeUs)
    }

    override fun release() {
        delegate.release()
    }

    override fun getUnderlyingImplementation(): Extractor =
        delegate.underlyingImplementation

    private fun replaceSeekMap(seekMap: SeekMap): SeekMap {
        if (seekMap.isSeekable) return seekMap
        val durationUs = seekMap.durationUs
        if (durationUs == C.TIME_UNSET || durationUs <= 0L) return seekMap
        lastDurationUs = durationUs
        val built = maybeRebuildIndex(resolveTip(), emit = false)
        return built ?: seekMap
    }

    private fun maybeRebuildIndex(tip: Long, emit: Boolean): IndexSeekMap? {
        val path = filePathProvider() ?: return null
        if (!shouldRebuild(tip)) {
            return currentSeekMap()
        }
        val file = File(path)
        if (!file.exists()) return null
        val index = MatroskaClusterIndexer.indexFile(file, tip) ?: return null
        indexedPositions = index.positions
        indexedTimesUs = index.timesUs
        lastIndexedTip = tip
        val durationUs = lastDurationUs
        val map = if (durationUs != C.TIME_UNSET && durationUs > 0L) {
            MatroskaClusterIndexer.toSeekMap(index, durationUs)
        } else {
            null
        }
        if (emit && map != null) {
            appOutput?.seekMap(map)
        }
        return map
    }

    private fun currentSeekMap(): IndexSeekMap? {
        val positions = indexedPositions ?: return null
        val timesUs = indexedTimesUs ?: return null
        val durationUs = lastDurationUs
        if (positions.size < MatroskaClusterIndexer.MIN_CLUSTERS) return null
        if (positions.size != timesUs.size) return null
        if (durationUs == C.TIME_UNSET || durationUs <= 0L) return null
        return IndexSeekMap(positions, timesUs, durationUs)
    }

    private fun shouldRebuild(tip: Long): Boolean {
        if (indexedPositions == null) return true
        if (lastIndexedTip < 0L) return true
        return tip - lastIndexedTip >= MatroskaClusterIndexer.REBUILD_GROWTH_BYTES
    }

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

    private fun snapAndClamp(position: Long, tip: Long): Long {
        val safe = MatroskaClusterIndexer.safeTip(tip)
        val clampLimit = when {
            safe > 1L -> safe - 1L
            tip > 1L -> tip - 1L
            else -> 0L
        }
        val clamped = when {
            tip > 0L -> position.coerceIn(0L, clampLimit)
            else -> position.coerceAtLeast(0L)
        }
        val positions = indexedPositions
        if (positions == null || positions.isEmpty()) return clamped
        val snapped = MatroskaClusterIndexer.nearestAtOrBefore(positions, clamped)
        return if (clampLimit > 0L) snapped.coerceIn(0L, clampLimit) else snapped.coerceAtLeast(0L)
    }
}
