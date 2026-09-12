package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
import androidx.media3.extractor.mkv.MatroskaExtractor
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
 * Supports progressive partial emission and a near-playback-first expanding-window demux when a
 * [SeekMap] is available so the live panel paints mid-movie with continuous growth (many small
 * updates) instead of a harsh Phase-A / Phase-B cliff.
 *
 * Bitmap image subtitles (PGS / VobSub / DVB) are not converted to text and yield an empty list.
 */
@UnstableApi
object EmbeddedSubtitleCueExtractor {

    private val subtitleParserFactory = DefaultSubtitleParserFactory()
    private val cueDecoder = CueDecoder()

    /** Emit a partial list at least this often while demuxing (tight for steady UI growth). */
    private const val PARTIAL_EMIT_MIN_INTERVAL_MS = 120L
    /** Or whenever at least this many new cues have been decoded since the last emit. */
    private const val PARTIAL_EMIT_CUE_BATCH = 25

    fun extract(
        context: Context,
        mediaUri: Uri,
        selectedFormat: Format,
        preferredTextTrackIndex: Int,
        playbackPositionMs: Long = 0L,
        onPartialCues: ((List<TimedCue>) -> Unit)? = null,
    ): List<TimedCue> {
        if (isBitmapSubtitle(selectedFormat)) return emptyList()

        val dataSource: DataSource = DefaultDataSource.Factory(context).createDataSource()
        return try {
            demuxAndConvert(
                dataSource = dataSource,
                mediaUri = mediaUri,
                selectedFormat = selectedFormat,
                preferredTextTrackIndex = preferredTextTrackIndex,
                playbackPositionMs = playbackPositionMs,
                onPartialCues = onPartialCues,
            )
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
        if (!selected.language.isNullOrBlank() && selected.language == candidate.language) {
            score += 40
        } else if (languagesLooselyMatch(selected.language, candidate.language)) {
            score += 30
        }
        if (!selected.label.isNullOrBlank() && selected.label == candidate.label) score += 30

        val selectedMime = originalSubtitleMime(selected)
        val candidateMime = originalSubtitleMime(candidate)
        if (!selectedMime.isNullOrBlank() && selectedMime == candidateMime) score += 25
        if (!selected.codecs.isNullOrBlank() && selected.codecs == candidate.codecs) score += 15
        if (candidateIndex == preferredIndex) score += 10
        return score
    }

    /** Match en↔eng style codes when Format normalization did not already equate them. */
    private fun languagesLooselyMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a.equals(b, ignoreCase = true)) return true
        val a2 = a.lowercase()
        val b2 = b.lowercase()
        return a2.startsWith(b2) || b2.startsWith(a2)
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

    private fun demuxAndConvert(
        dataSource: DataSource,
        mediaUri: Uri,
        selectedFormat: Format,
        preferredTextTrackIndex: Int,
        playbackPositionMs: Long,
        onPartialCues: ((List<TimedCue>) -> Unit)?,
    ): List<TimedCue> {
        // Prefer Media3 cue-transcoding with normal cue seeking (keeps expanding-window
        // near-playback seed when SeekMap is available). If that yields nothing for a text
        // track, retry with EOF cue-seek disabled + raw samples so ASS/SSA/SRT in awkward
        // MKVs still have a chance.
        val transcoded = demuxOnce(
            dataSource = dataSource,
            mediaUri = mediaUri,
            selectedFormat = selectedFormat,
            preferredTextTrackIndex = preferredTextTrackIndex,
            playbackPositionMs = playbackPositionMs,
            onPartialCues = onPartialCues,
            emitRawSubtitleData = false,
            disableSeekForCues = false,
        )
        if (transcoded.isNotEmpty() || isBitmapSubtitle(selectedFormat)) {
            return transcoded
        }
        return demuxOnce(
            dataSource = dataSource,
            mediaUri = mediaUri,
            selectedFormat = selectedFormat,
            preferredTextTrackIndex = preferredTextTrackIndex,
            playbackPositionMs = playbackPositionMs,
            onPartialCues = onPartialCues,
            emitRawSubtitleData = true,
            disableSeekForCues = true,
        )
    }

    private fun demuxOnce(
        dataSource: DataSource,
        mediaUri: Uri,
        selectedFormat: Format,
        preferredTextTrackIndex: Int,
        playbackPositionMs: Long,
        onPartialCues: ((List<TimedCue>) -> Unit)?,
        emitRawSubtitleData: Boolean,
        disableSeekForCues: Boolean,
    ): List<TimedCue> {
        val output = CollectingExtractorOutput(selectedFormat, preferredTextTrackIndex)
        val matroskaFlags =
            if (disableSeekForCues) MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES else 0
        val extractorsFactory = DefaultExtractorsFactory()
            .setSubtitleParserFactory(subtitleParserFactory)
            .setMatroskaExtractorFlags(matroskaFlags)
            .setTextTrackTranscodingEnabled(!emitRawSubtitleData)
        val extractors = extractorsFactory.createExtractors(mediaUri, emptyMap())
        if (extractors.isEmpty()) return emptyList()

        runCatching { dataSource.close() }
        var input: ExtractorInput = openInput(dataSource, mediaUri, position = 0L)
        val extractor = sniffExtractor(extractors, input) ?: return emptyList()
        input.resetPeekPosition()
        extractor.init(output)

        val positionHolder = PositionHolder()
        val emitter = ProgressiveEmitter(onPartialCues)
        val seen = HashSet<String>()
        val merged = mutableListOf<TimedCue>()
        // Incremental decode cursor per selected track sample list identity.
        var decodedSampleCount = 0

        fun reopenAt(position: Long, timeUs: Long) {
            runCatching { dataSource.close() }
            input = openInput(dataSource, mediaUri, position = position)
            extractor.seek(position, timeUs)
        }

        fun readOnce(): Int {
            return when (extractor.read(input, positionHolder)) {
                Extractor.RESULT_CONTINUE -> Extractor.RESULT_CONTINUE
                Extractor.RESULT_END_OF_INPUT -> Extractor.RESULT_END_OF_INPUT
                Extractor.RESULT_SEEK -> {
                    reopenAt(positionHolder.position, timeUs = 0L)
                    Extractor.RESULT_CONTINUE
                }
                else -> Extractor.RESULT_END_OF_INPUT
            }
        }

        fun selectedTrackOrNull(): CollectedTextTrack? {
            val tracks = output.textTracksInOrder()
            return selectBestTextTrack(tracks, selectedFormat, preferredTextTrackIndex)
                ?.takeUnless { isBitmapSubtitle(it.format) }
        }

        fun refreshMergedFromSelected(): List<TimedCue> {
            val track = selectedTrackOrNull() ?: return merged.sortedBy { it.startMs }
            if (track.samples.size < decodedSampleCount) {
                // Sample list was trimmed between windows; reset decode cursor.
                decodedSampleCount = 0
            }
            appendNewSamples(track, decodedSampleCount, seen, merged)
            decodedSampleCount = track.samples.size
            val snapshot = merged.sortedBy { it.startMs }
            emitter.maybeEmit(snapshot)
            return snapshot
        }

        fun seekToTimeUs(timeUs: Long, seekMap: SeekMap) {
            val seekPoints = seekMap.getSeekPoints(timeUs)
            reopenAt(seekPoints.first.position, timeUs)
            output.clearAllSamples()
            decodedSampleCount = 0
        }

        /**
         * Demux [window] (times in ms), merging into [merged]/[seen].
         * Stops at window end, EOF, or (for seed) [stopAfterCueCount].
         * Returns whether EOF was hit during this read.
         */
        fun readWindow(
            window: ExpandingCueWindow.Window,
            seekMap: SeekMap,
            stopAfterCueCount: Int? = null,
            dropAtOrAfterMs: Long? = null,
        ): Boolean {
            val startUs = Util.msToUs(window.startMs)
            val endUs = Util.msToUs(window.endMs)
            val dropUs = dropAtOrAfterMs?.let { Util.msToUs(it) }
            val cuesBefore = merged.size
            seekToTimeUs(startUs, seekMap)
            var hitEof = false
            while (true) {
                val result = readOnce()
                if (dropUs != null) {
                    output.dropSamplesAtOrAfter(dropUs)
                }
                refreshMergedFromSelected()
                val newest = output.newestSampleTimeUs()
                if (newest != C.TIME_UNSET && newest >= endUs) {
                    break
                }
                if (stopAfterCueCount != null &&
                    merged.size - cuesBefore >= stopAfterCueCount
                ) {
                    break
                }
                if (result == Extractor.RESULT_END_OF_INPUT) {
                    hitEof = true
                    break
                }
            }
            emitter.maybeEmit(merged.sortedBy { it.startMs }, force = true)
            return hitEof
        }

        try {
            // Bootstrap until SeekMap typically arrives.
            var bootstrap = 0
            while (bootstrap < 64 && output.seekMap == null) {
                if (readOnce() == Extractor.RESULT_END_OF_INPUT) break
                bootstrap++
            }

            val seekMap = output.seekMap
            if (seekMap != null &&
                ExpandingCueWindow.shouldUseExpandingWindow(
                    playbackPositionMs,
                    seekable = seekMap.isSeekable,
                )
            ) {
                // Fresh buffers for expanding-window pass (drop bootstrap leftovers).
                output.clearAllSamples()
                decodedSampleCount = 0
                merged.clear()
                seen.clear()

                val seed = ExpandingCueWindow.seedWindow(playbackPositionMs)
                var coverage = ExpandingCueWindow.Coverage(
                    startMs = seed.startMs,
                    endMs = seed.endMs,
                    reachedStart = seed.startMs <= 0L,
                    reachedEof = false,
                )

                // Seed: modest band around playhead — emit ASAP (cue-count or time bound).
                val seedEof = readWindow(
                    window = seed,
                    seekMap = seekMap,
                    stopAfterCueCount = ExpandingCueWindow.SEED_TARGET_CUES,
                )
                if (seedEof) {
                    coverage = coverage.copy(reachedEof = true)
                } else {
                    // If we stopped early on cue count, shrink covered end to what we scanned.
                    val newest = output.newestSampleTimeUs()
                    if (newest != C.TIME_UNSET) {
                        val newestMs = Util.usToMs(newest)
                        if (newestMs < coverage.endMs) {
                            coverage = coverage.copy(endMs = newestMs.coerceAtLeast(coverage.startMs))
                        }
                    }
                }

                var stepMs = ExpandingCueWindow.INITIAL_EXPAND_STEP_MS
                var guard = 0
                while (!coverage.isComplete && guard < 64) {
                    guard++

                    val earlier = ExpandingCueWindow.nextEarlierWindow(coverage.startMs, stepMs)
                    if (earlier != null) {
                        readWindow(
                            window = earlier,
                            seekMap = seekMap,
                            // Avoid re-decoding samples already covered by later windows.
                            dropAtOrAfterMs = coverage.startMs,
                        )
                        coverage = coverage.copy(
                            startMs = earlier.startMs,
                            reachedStart = earlier.startMs <= 0L,
                        )
                    } else {
                        coverage = coverage.copy(reachedStart = true)
                    }

                    if (!coverage.reachedEof) {
                        val later = ExpandingCueWindow.nextLaterWindow(
                            coveredEndMs = coverage.endMs,
                            stepMs = stepMs,
                            reachedEof = false,
                        )
                        if (later != null) {
                            val sizeBefore = merged.size
                            val laterEof = readWindow(window = later, seekMap = seekMap)
                            val newest = output.newestSampleTimeUs()
                            val scannedEndMs = when {
                                laterEof -> later.endMs
                                newest != C.TIME_UNSET ->
                                    Util.usToMs(newest).coerceAtLeast(coverage.endMs)
                                else -> later.endMs
                            }
                            // No forward progress and no EOF flag → treat as done (seek stuck at end).
                            val stuck =
                                !laterEof &&
                                    merged.size == sizeBefore &&
                                    scannedEndMs <= coverage.endMs
                            coverage = coverage.copy(
                                endMs = scannedEndMs.coerceAtLeast(coverage.endMs),
                                reachedEof = laterEof || stuck,
                            )
                        }
                    }

                    stepMs = ExpandingCueWindow.growStep(stepMs)
                }

                // If we never hit EOF on a later pass (e.g. duration unknown and seeks
                // undershoot), finish with one forward read from current coverage end.
                if (!coverage.reachedEof) {
                    val finish = ExpandingCueWindow.Window(
                        startMs = coverage.endMs,
                        // Large bound; EOF is the real stop.
                        endMs = coverage.endMs + 24 * 60 * 60 * 1000L,
                    )
                    readWindow(window = finish, seekMap = seekMap)
                }
            } else {
                // Non-seekable / early playback: single forward pass with frequent partials.
                while (true) {
                    val result = readOnce()
                    refreshMergedFromSelected()
                    if (result == Extractor.RESULT_END_OF_INPUT) break
                }
            }

            val finalCues = refreshMergedFromSelected()
            emitter.maybeEmit(finalCues, force = true)
            return finalCues
        } finally {
            extractor.release()
        }
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

    private fun appendNewSamples(
        track: CollectedTextTrack,
        alreadyDecoded: Int,
        seen: HashSet<String>,
        into: MutableList<TimedCue>,
    ): List<TimedCue> {
        val format = track.format
        val startIndex = alreadyDecoded.coerceIn(0, track.samples.size)

        if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
            for (i in startIndex until track.samples.size) {
                val sample = track.samples[i]
                val decoded = runCatching {
                    cueDecoder.decode(sample.timeUs, sample.data, 0, sample.data.size)
                }.getOrNull() ?: continue
                for (timed in cuesWithTimingToTimedCues(decoded)) {
                    val key = "${timed.startMs}|${timed.endMs}|${timed.text}"
                    if (seen.add(key)) into += timed
                }
            }
            return into
        }

        if (!subtitleParserFactory.supportsFormat(format)) {
            return into
        }
        val parser = subtitleParserFactory.create(format)
        for (i in startIndex until track.samples.size) {
            val sample = track.samples[i]
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
                    if (seen.add(key)) into += timed
                }
            }
        }
        parser.reset()
        return into
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

    private class ProgressiveEmitter(
        private val onPartialCues: ((List<TimedCue>) -> Unit)?,
    ) {
        private var lastEmitElapsedMs = 0L
        private var lastEmitSize = 0

        fun maybeEmit(cues: List<TimedCue>, force: Boolean = false) {
            val callback = onPartialCues ?: return
            if (cues.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            val grew = cues.size - lastEmitSize
            if (!force &&
                grew < PARTIAL_EMIT_CUE_BATCH &&
                now - lastEmitElapsedMs < PARTIAL_EMIT_MIN_INTERVAL_MS
            ) {
                return
            }
            lastEmitElapsedMs = now
            lastEmitSize = cues.size
            callback(cues)
        }
    }

    internal data class CollectedTextTrack(
        val format: Format,
        val samples: List<Sample>,
    )

    internal data class Sample(
        val timeUs: Long,
        val data: ByteArray,
    )

    private class CollectingExtractorOutput(
        private val selectedFormat: Format,
        private val preferredTextTrackIndex: Int,
    ) : ExtractorOutput {
        private val textTrackOutputs = linkedMapOf<Int, CollectingTrackOutput>()
        private val discarding = DiscardingTrackOutput()
        private val textOrder = mutableListOf<Int>()
        var seekMap: SeekMap? = null
            private set

        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_TEXT) return discarding
            return textTrackOutputs.getOrPut(id) {
                textOrder += id
                CollectingTrackOutput()
            }
        }

        override fun endTracks() {
            pruneToSelectedTrack()
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        fun textTracksInOrder(): List<CollectedTextTrack> {
            pruneToSelectedTrack()
            return textOrder.mapNotNull { id ->
                val output = textTrackOutputs[id] ?: return@mapNotNull null
                if (output.discardSamples) return@mapNotNull null
                val format = output.format ?: return@mapNotNull null
                CollectedTextTrack(format = format, samples = output.samples)
            }
        }

        fun clearAllSamples() {
            textTrackOutputs.values.forEach { it.clearSamples() }
        }

        fun dropSamplesAtOrAfter(joinTimeUs: Long) {
            pruneToSelectedTrack()
            textTrackOutputs.values.forEach { output ->
                if (output.discardSamples) return@forEach
                output.samples.removeAll { sample ->
                    sample.timeUs != C.TIME_UNSET && sample.timeUs >= joinTimeUs
                }
            }
        }

        fun newestSampleTimeUs(): Long {
            var max = C.TIME_UNSET
            textTrackOutputs.values.forEach { output ->
                if (output.discardSamples) return@forEach
                for (sample in output.samples) {
                    if (sample.timeUs != C.TIME_UNSET &&
                        (max == C.TIME_UNSET || sample.timeUs > max)
                    ) {
                        max = sample.timeUs
                    }
                }
            }
            return max
        }

        private fun pruneToSelectedTrack() {
            if (textTrackOutputs.isEmpty()) return
            val scored = textOrder.mapIndexedNotNull { index, id ->
                val output = textTrackOutputs[id] ?: return@mapIndexedNotNull null
                val format = output.format ?: return@mapIndexedNotNull null
                id to scoreTrack(format, selectedFormat, index, preferredTextTrackIndex)
            }
            if (scored.isEmpty()) return
            val bestId = scored.maxBy { it.second }.first
            textTrackOutputs.forEach { (id, output) ->
                if (id != bestId) {
                    output.discardSamples = true
                    output.clearSamples()
                }
            }
        }
    }

    private class CollectingTrackOutput : TrackOutput {
        var format: Format? = null
            private set
        val samples = mutableListOf<Sample>()
        var discardSamples: Boolean = false

        private var sampleData = ByteArray(0)
        private var sampleDataBytes = 0

        fun clearSamples() {
            samples.clear()
            sampleDataBytes = 0
        }

        override fun format(format: Format) {
            this.format = format
            if (isBitmapSubtitle(format)) {
                discardSamples = true
                clearSamples()
            }
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
            if (discardSamples) {
                sampleDataBytes = 0
                return
            }
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
