package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import java.io.EOFException

/**
 * Demuxes a local/http media container and builds a full text cue timeline for one embedded
 * subtitle track using Media3 extractors + [DefaultSubtitleParserFactory].
 *
 * Bitmap image subtitles (PGS / VobSub / DVB) are not converted to text and yield an empty list.
 */
@UnstableApi
object EmbeddedSubtitleCueExtractor {

    private val subtitleParserFactory = DefaultSubtitleParserFactory()
    private val cueDecoder = CueDecoder()

    fun extract(
        context: Context,
        mediaUri: Uri,
        selectedFormat: Format,
        preferredTextTrackIndex: Int,
    ): List<TimedCue> {
        if (isBitmapSubtitle(selectedFormat)) return emptyList()

        val dataSource: DataSource = DefaultDataSource.Factory(context).createDataSource()
        return try {
            val tracks = demuxTextTracks(dataSource, mediaUri)
            val matched = selectBestTextTrack(tracks, selectedFormat, preferredTextTrackIndex)
                ?: return emptyList()
            if (isBitmapSubtitle(matched.format)) return emptyList()
            samplesToTimedCues(matched)
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { dataSource.close() }
        }
    }

    fun isBitmapSubtitle(format: Format): Boolean =
        isBitmapMime(format.sampleMimeType) || isBitmapMime(format.codecs)

    fun isBitmapMime(mimeOrCodec: String?): Boolean =
        mimeOrCodec == MimeTypes.APPLICATION_PGS ||
            mimeOrCodec == MimeTypes.APPLICATION_VOBSUB ||
            mimeOrCodec == MimeTypes.APPLICATION_DVBSUBS

    /** Original container mime when the player already exposed Media3-cues. */
    fun originalSubtitleMime(format: Format): String? {
        val codecs = format.codecs
        if (!codecs.isNullOrBlank() && codecs != MimeTypes.APPLICATION_MEDIA3_CUES) {
            return codecs
        }
        val mime = format.sampleMimeType
        return mime?.takeIf { it != MimeTypes.APPLICATION_MEDIA3_CUES }
    }

    internal fun selectBestTextTrack(
        tracks: List<CollectedTextTrack>,
        selectedFormat: Format,
        preferredTextTrackIndex: Int,
    ): CollectedTextTrack? {
        if (tracks.isEmpty()) return null
        var best: CollectedTextTrack? = null
        var bestScore = Int.MIN_VALUE
        tracks.forEachIndexed { index, track ->
            val score = scoreTrack(track.format, selectedFormat, index, preferredTextTrackIndex)
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }
        return best
    }

    internal fun scoreTrack(
        candidate: Format,
        selected: Format,
        candidateIndex: Int,
        preferredIndex: Int,
    ): Int {
        var score = 0
        if (!selected.id.isNullOrBlank() && selected.id == candidate.id) score += 100
        if (!selected.language.isNullOrBlank() && selected.language == candidate.language) score += 40
        if (!selected.label.isNullOrBlank() && selected.label == candidate.label) score += 30

        val selectedMime = originalSubtitleMime(selected)
        val candidateMime = originalSubtitleMime(candidate)
        if (!selectedMime.isNullOrBlank() && selectedMime == candidateMime) score += 25
        if (!selected.codecs.isNullOrBlank() && selected.codecs == candidate.codecs) score += 15
        if (candidateIndex == preferredIndex) score += 10
        return score
    }

    internal fun cuesWithTimingToTimedCues(cuesWithTiming: CuesWithTiming): List<TimedCue> {
        val startMs = timeUsToMsOrUnset(cuesWithTiming.startTimeUs)
        val endMs = when {
            cuesWithTiming.endTimeUs != C.TIME_UNSET -> Util.usToMs(cuesWithTiming.endTimeUs)
            startMs != null && cuesWithTiming.durationUs != C.TIME_UNSET ->
                startMs + Util.usToMs(cuesWithTiming.durationUs)
            else -> null
        }
        if (startMs == null || endMs == null || endMs < startMs) return emptyList()

        val text = cuesWithTiming.cues
            .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString("\n")
            .stripSimpleMarkup()
            .trim()
        if (text.isEmpty()) return emptyList()
        return listOf(TimedCue(startMs = startMs, endMs = endMs, text = text))
    }

    private fun demuxTextTracks(dataSource: DataSource, mediaUri: Uri): List<CollectedTextTrack> {
        val output = CollectingExtractorOutput()
        // Text-track transcoding defaults to enabled in Media3 1.11 and emits
        // APPLICATION_MEDIA3_CUES samples decoded via DefaultSubtitleParserFactory.
        val extractorsFactory = DefaultExtractorsFactory()
            .setSubtitleParserFactory(subtitleParserFactory)
        val extractors = extractorsFactory.createExtractors(mediaUri, emptyMap())
        if (extractors.isEmpty()) return emptyList()

        var input: ExtractorInput = openInput(dataSource, mediaUri, position = 0L)
        val extractor = sniffExtractor(extractors, input) ?: return emptyList()
        // sniff() peeks; reset peek before init/read.
        input.resetPeekPosition()
        extractor.init(output)

        val positionHolder = PositionHolder()
        try {
            while (true) {
                when (val result = extractor.read(input, positionHolder)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> {
                        runCatching { dataSource.close() }
                        input = openInput(dataSource, mediaUri, position = positionHolder.position)
                        extractor.seek(positionHolder.position, 0L)
                    }
                    else -> break
                }
            }
        } finally {
            extractor.release()
        }
        return output.textTracksInOrder()
    }

    private fun sniffExtractor(extractors: Array<Extractor>, input: ExtractorInput): Extractor? {
        for (extractor in extractors) {
            try {
                if (extractor.sniff(input)) {
                    return extractor
                }
            } catch (_: EOFException) {
                // try next
            } finally {
                input.resetPeekPosition()
            }
        }
        return null
    }

    private fun openInput(dataSource: DataSource, mediaUri: Uri, position: Long): ExtractorInput {
        val dataSpec = DataSpec.Builder()
            .setUri(mediaUri)
            .setPosition(position)
            .build()
        val length = dataSource.open(dataSpec)
        return DefaultExtractorInput(dataSource, position, length)
    }

    private fun samplesToTimedCues(track: CollectedTextTrack): List<TimedCue> {
        val format = track.format
        val cues = mutableListOf<TimedCue>()
        val seen = HashSet<String>()

        if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
            for (sample in track.samples) {
                val decoded = runCatching {
                    cueDecoder.decode(sample.timeUs, sample.data, 0, sample.data.size)
                }.getOrNull() ?: continue
                for (timed in cuesWithTimingToTimedCues(decoded)) {
                    val key = "${timed.startMs}|${timed.endMs}|${timed.text}"
                    if (seen.add(key)) cues += timed
                }
            }
            return cues.sortedBy { it.startMs }
        }

        if (!subtitleParserFactory.supportsFormat(format)) {
            return emptyList()
        }
        val parser = subtitleParserFactory.create(format)
        for (sample in track.samples) {
            val parsed = mutableListOf<CuesWithTiming>()
            runCatching {
                parser.parse(
                    sample.data,
                    0,
                    sample.data.size,
                    SubtitleParser.OutputOptions.allCues(),
                ) { parsed += it }
            }
            for (cuesWithTiming in parsed) {
                val adjusted = adjustTiming(cuesWithTiming, sample.timeUs)
                for (timed in cuesWithTimingToTimedCues(adjusted)) {
                    val key = "${timed.startMs}|${timed.endMs}|${timed.text}"
                    if (seen.add(key)) cues += timed
                }
            }
        }
        parser.reset()
        return cues.sortedBy { it.startMs }
    }

    private fun adjustTiming(cues: CuesWithTiming, sampleTimeUs: Long): CuesWithTiming {
        if (cues.startTimeUs != C.TIME_UNSET) return cues
        if (sampleTimeUs == C.TIME_UNSET) return cues
        val durationUs = when {
            cues.durationUs != C.TIME_UNSET -> cues.durationUs
            cues.endTimeUs != C.TIME_UNSET -> cues.endTimeUs - sampleTimeUs
            else -> C.TIME_UNSET
        }
        return CuesWithTiming(cues.cues, sampleTimeUs, durationUs)
    }

    private fun timeUsToMsOrUnset(timeUs: Long): Long? =
        if (timeUs == C.TIME_UNSET) null else Util.usToMs(timeUs)

    private fun String.stripSimpleMarkup(): String =
        replace(HTML_TAG, "")
            .replace(ASS_OVERRIDE_TAG, "")
            .replace(SRT_POSITION_TAG, "")

    private val HTML_TAG = Regex("""</?[^>]+>""")
    private val ASS_OVERRIDE_TAG = Regex("""\{[^}]*\}""")
    private val SRT_POSITION_TAG = Regex("""\{\\an\d\}""")

    internal data class CollectedTextTrack(
        val format: Format,
        val samples: List<Sample>,
    )

    internal data class Sample(
        val timeUs: Long,
        val data: ByteArray,
    )

    private class CollectingExtractorOutput : ExtractorOutput {
        private val textTrackOutputs = linkedMapOf<Int, CollectingTrackOutput>()
        private val discarding = DiscardingTrackOutput()
        private val textOrder = mutableListOf<Int>()

        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_TEXT) return discarding
            return textTrackOutputs.getOrPut(id) {
                textOrder += id
                CollectingTrackOutput()
            }
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit

        fun textTracksInOrder(): List<CollectedTextTrack> =
            textOrder.mapNotNull { id ->
                val output = textTrackOutputs[id] ?: return@mapNotNull null
                val format = output.format ?: return@mapNotNull null
                CollectedTextTrack(format = format, samples = output.samples.toList())
            }
    }

    private class CollectingTrackOutput : TrackOutput {
        var format: Format? = null
            private set
        val samples = mutableListOf<Sample>()

        private var sampleData = ByteArray(0)
        private var sampleDataBytes = 0

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            input: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            ensureCapacity(sampleDataBytes + length)
            val read = input.read(sampleData, sampleDataBytes, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            sampleDataBytes += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            ensureCapacity(sampleDataBytes + length)
            data.readBytes(sampleData, sampleDataBytes, length)
            sampleDataBytes += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val end = sampleDataBytes - offset
            val start = end - size
            if (start < 0 || end > sampleDataBytes) {
                sampleDataBytes = 0
                return
            }
            val bytes = sampleData.copyOfRange(start, end)
            samples += Sample(timeUs = timeUs, data = bytes)
            sampleDataBytes = 0
        }

        private fun ensureCapacity(required: Int) {
            if (sampleData.size >= required) return
            sampleData = sampleData.copyOf(maxOf(required, sampleData.size * 2).coerceAtLeast(4096))
        }
    }
}
