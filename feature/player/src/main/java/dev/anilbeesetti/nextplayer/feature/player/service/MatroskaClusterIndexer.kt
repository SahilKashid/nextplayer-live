package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.IndexSeekMap
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Builds an EBML-validated Cluster index over `[0, safeTip)` for incomplete local Matroska.
 *
 * Walks level-1 elements from the EBML header / Segment with real ID+size varints (see
 * [EbmlProbe]) and records a seek point only when a Cluster has a valid size and a Timecode
 * in the first [TIMECODE_PROBE_BYTES] of payload. Unvalidated `1F43B675` matches and
 * declared-length byte mapping are never used.
 */
@UnstableApi
object MatroskaClusterIndexer {

    const val ID_EBML = 0x1A45DFA3L
    const val ID_SEGMENT = 0x18538067L
    const val ID_SEEK_HEAD = 0x114D9B74L
    const val ID_INFO = 0x1549A966L
    const val ID_TRACKS = 0x1654AE6BL
    const val ID_CLUSTER = 0x1F43B675L
    const val ID_CUES = 0x1C53BB6BL
    const val ID_CHAPTERS = 0x1043A770L
    const val ID_TAGS = 0x1254C367L
    const val ID_ATTACHMENTS = 0x1941A469L
    const val ID_TIMECODE = 0xE7L
    const val ID_TIMECODE_SCALE = 0x2AD7B1L

    const val DEFAULT_TIMECODE_SCALE = 1_000_000L
    const val MAX_CLUSTER_PAYLOAD = 64L * 1024L * 1024L
    const val MAX_SEEK_POINTS = 2000
    const val TIMECODE_PROBE_BYTES = 64
    const val SAFETY_MARGIN_BYTES = 1024L * 1024L
    const val MIN_CLUSTERS = 2
    const val REBUILD_GROWTH_BYTES = 8L * 1024L * 1024L

    class ClusterIndex(
        val positions: LongArray,
        val timesUs: LongArray,
        val indexedUpTo: Long,
        val timecodeScale: Long,
    ) {
        val size: Int get() = positions.size
    }

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

    fun indexFile(file: File, tip: Long, safeTip: Long = safeTip(tip)): ClusterIndex? {
        if (!file.exists() || tip < 16L || safeTip < 16L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                index(EbmlProbe.Reader.fromRaf(raf), tip, safeTip)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun indexBytes(
        data: ByteArray,
        tip: Long = data.size.toLong(),
        safeTip: Long = safeTip(tip),
    ): ClusterIndex? {
        if (data.size < 16 || tip < 16L || safeTip < 16L) return null
        return try {
            index(EbmlProbe.Reader.fromBytes(data), tip, safeTip)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Media3 1.11 ctor is `(positions, timesUs, durationUs)`.
     * Do **not** swap with [IndexSeekMap.addSeekPoint] `(timeUs, position)`.
     */
    fun toSeekMap(index: ClusterIndex, durationUs: Long): IndexSeekMap? {
        if (index.size < MIN_CLUSTERS) return null
        return IndexSeekMap(index.positions, index.timesUs, durationUs)
    }

    fun nearestAtOrBefore(positions: LongArray, target: Long): Long {
        if (positions.isEmpty()) return 0L
        if (target <= positions[0]) return positions[0]
        val last = positions.lastIndex
        if (target >= positions[last]) return positions[last]
        var lo = 0
        var hi = last
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val value = positions[mid]
            when {
                value == target -> return value
                value < target -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return positions[hi.coerceAtLeast(0)]
    }

    private fun index(reader: EbmlProbe.Reader, tip: Long, safeTip: Long): ClusterIndex? {
        val limit = minOf(safeTip, tip, reader.length).coerceAtLeast(0L)
        if (limit < 16L) return null

        val ebmlId = reader.readId(limit) ?: return null
        if (ebmlId.value != ID_EBML) return null
        val ebmlSize = reader.readSize(limit) ?: return null
        if (ebmlSize.isUnknownSize || ebmlSize.value < 0L) return null
        if (!reader.skip(ebmlSize.value, limit)) return null

        val segmentId = reader.readId(limit) ?: return null
        if (segmentId.value != ID_SEGMENT) return null
        val segmentSize = reader.readSize(limit) ?: return null
        val segmentEnd = if (segmentSize.isUnknownSize) {
            limit
        } else {
            minOf(limit, reader.position + segmentSize.value)
        }

        var timecodeScale = DEFAULT_TIMECODE_SCALE
        val positions = ArrayList<Long>(64)
        val timesUs = ArrayList<Long>(64)

        while (reader.position < segmentEnd && positions.size < MAX_SEEK_POINTS) {
            val elemStart = reader.position
            val id = reader.readId(segmentEnd) ?: break
            val size = reader.readSize(segmentEnd) ?: break
            val knownSize = !size.isUnknownSize
            val payloadStart = reader.position
            if (knownSize && size.value < 0L) break
            val payloadEnd = if (knownSize) payloadStart + size.value else segmentEnd
            val truncated = knownSize && payloadEnd > segmentEnd

            when (id.value) {
                ID_CLUSTER -> {
                    if (elemStart >= safeTip) break
                    val sizeOk = size.isUnknownSize || size.value in 1L..MAX_CLUSTER_PAYLOAD
                    if (!sizeOk) {
                        break
                    }
                    val probeEnd = minOf(
                        payloadEnd,
                        payloadStart + TIMECODE_PROBE_BYTES,
                        segmentEnd,
                    )
                    val timecode = if (probeEnd > payloadStart) {
                        readClusterTimecode(reader, payloadStart, probeEnd)
                    } else {
                        null
                    }
                    if (timecode != null) {
                        positions.add(elemStart)
                        timesUs.add(scaleToUs(timecode, timecodeScale))
                    }
                    if (truncated) break
                    if (knownSize) {
                        reader.seek(minOf(payloadEnd, segmentEnd))
                    } else {
                        walkUntilLevel1(reader, segmentEnd)
                    }
                }
                ID_INFO -> {
                    val infoEnd = if (knownSize) minOf(payloadEnd, segmentEnd) else segmentEnd
                    val parsed = readTimecodeScale(reader, payloadStart, infoEnd)
                    if (parsed != null && parsed > 0L) timecodeScale = parsed
                    if (knownSize) {
                        reader.seek(minOf(payloadEnd, segmentEnd))
                    } else {
                        walkUntilLevel1(reader, segmentEnd)
                    }
                }
                else -> {
                    if (knownSize) {
                        if (!reader.skip(size.value, segmentEnd)) break
                    } else {
                        walkUntilLevel1(reader, segmentEnd)
                    }
                }
            }
        }

        if (positions.size < MIN_CLUSTERS) return null
        return ClusterIndex(
            positions = positions.toLongArray(),
            timesUs = timesUs.toLongArray(),
            indexedUpTo = limit,
            timecodeScale = timecodeScale,
        )
    }

    private fun isLevel1(id: Long): Boolean = when (id) {
        ID_SEEK_HEAD,
        ID_INFO,
        ID_TRACKS,
        ID_CLUSTER,
        ID_CUES,
        ID_CHAPTERS,
        ID_TAGS,
        ID_ATTACHMENTS,
        -> true
        else -> false
    }

    private fun walkUntilLevel1(reader: EbmlProbe.Reader, limit: Long) {
        while (reader.position < limit) {
            val start = reader.position
            val id = reader.readId(limit) ?: return
            val size = reader.readSize(limit) ?: return
            if (isLevel1(id.value)) {
                reader.seek(start)
                return
            }
            if (size.isUnknownSize || size.value < 0L) return
            if (!reader.skip(size.value, limit)) return
        }
    }

    private fun readClusterTimecode(
        reader: EbmlProbe.Reader,
        payloadStart: Long,
        probeEnd: Long,
    ): Long? {
        reader.seek(payloadStart)
        while (reader.position < probeEnd) {
            val id = reader.readId(probeEnd) ?: return null
            val size = reader.readSize(probeEnd) ?: return null
            if (size.isUnknownSize || size.value < 0L) return null
            if (id.value == ID_TIMECODE) {
                if (size.value !in 1L..8L) return null
                return reader.readUnsignedInteger(size.value.toInt(), probeEnd)
            }
            val valueEnd = reader.position + size.value
            if (valueEnd > probeEnd) return null
            reader.seek(valueEnd)
        }
        return null
    }

    private fun readTimecodeScale(
        reader: EbmlProbe.Reader,
        payloadStart: Long,
        infoEnd: Long,
    ): Long? {
        reader.seek(payloadStart)
        var found: Long? = null
        while (reader.position < infoEnd) {
            val start = reader.position
            val id = reader.readId(infoEnd) ?: break
            val size = reader.readSize(infoEnd) ?: break
            if (isLevel1(id.value)) {
                reader.seek(start)
                break
            }
            if (size.isUnknownSize || size.value < 0L) break
            if (id.value == ID_TIMECODE_SCALE && size.value in 1L..8L) {
                found = reader.readUnsignedInteger(size.value.toInt(), infoEnd)
            } else if (!reader.skip(size.value, infoEnd)) {
                break
            }
        }
        return found
    }

    private fun scaleToUs(timecode: Long, scale: Long): Long {
        if (timecode <= 0L) return 0L
        if (scale <= 0L) return 0L
        if (scale % 1000L == 0L) {
            val factor = scale / 1000L
            val product = timecode * factor
            if (factor != 0L && product / factor != timecode) {
                return Long.MAX_VALUE / 4
            }
            return product
        }
        return ((timecode.toDouble() * scale.toDouble()) / 1000.0).toLong()
    }
}
