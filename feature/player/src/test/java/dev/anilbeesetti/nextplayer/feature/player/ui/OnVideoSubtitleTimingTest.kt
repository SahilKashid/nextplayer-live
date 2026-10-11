package dev.anilbeesetti.nextplayer.feature.player.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import dev.anilbeesetti.nextplayer.feature.player.utils.subtitle.SubtitleCueParser
import io.github.anilbeesetti.nextlib.media3ext.renderer.NextTextRenderer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The text samples ExoPlayer's [androidx.media3.exoplayer.text.TextRenderer] consumes are the
 * transcoded cue timestamps. They must match the subtitle file, not file-time plus sample-time.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnVideoSubtitleTimingTest {

    @Test
    fun embeddedSrtSampleTimesMatchTheCueFile() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "subtitle-sync").apply { mkdirs() }
        val srt = copyResource("subtitle-sync/cues.srt", File(dir, "cues.srt"))
        val mkv = copyResource("subtitle-sync/srt.mkv", File(dir, "srt.mkv"))
        val expected = SubtitleCueParser.parseSrt(srt.readText()).map { it.startMs to it.text }
        assertEquals(
            listOf(0L to "zero", 500L to "half", 1_200L to "one point two", 3_000L to "three"),
            expected,
        )

        val rendered = transcodedCueStarts(mkv)
        assertEquals(expected, rendered)
    }

    @Test
    fun onVideoCuesArriveAtTheCueStartPosition() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "subtitle-sync-render").apply { mkdirs() }
        val mkv = copyResource("subtitle-sync/srt.mkv", File(dir, "srt.mkv"))
        val transcoded = transcode(mkv)
        val expected = listOf(
            0L to "zero",
            500L to "half",
            1_200L to "one point two",
            3_000L to "three",
        )
        val output = RecordingTextOutput()
        val delegate = TextRenderer(output, /* outputLooper= */ null)
        val renderer = NextTextRenderer(
            output,
            /* outputLooper= */ null,
            SubtitleDecoderFactory.DEFAULT,
            delegate,
        )
        // Default delay 0 and speed 1 must not move the text clock.
        assertEquals(1_700_000L, renderer.getOffsetAdjustedPositionUs(1_700_000L))
        renderer.init(0, PlayerId.UNSET, Clock.DEFAULT)
        renderer.enable(
            RendererConfiguration.DEFAULT,
            arrayOf(transcoded.format),
            CueSampleStream(transcoded.format, transcoded.samples),
            0L,
            false,
            true,
            0L,
            0L,
            MediaSource.MediaPeriodId(Any()),
        )
        renderer.start()
        val firstSeen = linkedMapOf<String, Long>()
        try {
            var positionUs = 0L
            while (positionUs <= 3_600_000L) {
                renderer.render(positionUs, positionUs)
                val text = output.text
                if (text.isNotEmpty() && text !in firstSeen) {
                    firstSeen[text] = Util.usToMs(positionUs)
                }
                positionUs += 10_000L
            }
        } finally {
            renderer.stop()
            renderer.disable()
        }
        for ((startMs, text) in expected) {
            val seenAt = firstSeen[text]
            assertTrue("missing '$text' in $firstSeen", seenAt != null)
            val lateBy = seenAt!! - startMs
            assertTrue(
                "'$text' at ${seenAt}ms, cue start ${startMs}ms (delta ${lateBy}ms) $firstSeen",
                lateBy in 0..10,
            )
        }
    }

    private fun transcodedCueStarts(file: File): List<Pair<Long, String>> {
        val decoder = CueDecoder()
        return transcode(file).samples.mapNotNull { sample ->
            val decoded = decoder.decode(sample.timeUs, sample.data, 0, sample.data.size)
            val text = cueText(decoded.cues)
            if (text.isEmpty()) null else Util.usToMs(decoded.startTimeUs) to text
        }
    }

    private fun transcode(file: File): TranscodedText {
        val context = RuntimeEnvironment.getApplication()
        val dataSource = DefaultDataSource.Factory(context).createDataSource()
        val output = CollectingTextOutput()
        val extractors = DefaultExtractorsFactory()
            .setSubtitleParserFactory(DefaultSubtitleParserFactory())
            .createExtractors(Uri.fromFile(file), emptyMap())
        dataSource.open(DataSpec(Uri.fromFile(file)))
        var input = DefaultExtractorInput(dataSource, 0L, C.LENGTH_UNSET.toLong())
        val extractor = extractors.first { candidate ->
            val result = candidate.sniff(input)
            input.resetPeekPosition()
            result
        }
        extractor.init(output)
        val holder = PositionHolder()
        while (true) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> {
                    dataSource.close()
                    dataSource.open(
                        DataSpec(Uri.fromFile(file), holder.position, C.LENGTH_UNSET.toLong()),
                    )
                    input = DefaultExtractorInput(
                        dataSource,
                        holder.position,
                        C.LENGTH_UNSET.toLong(),
                    )
                    extractor.seek(holder.position, 0L)
                }
            }
        }
        dataSource.close()
        return TranscodedText(
            format = checkNotNull(output.format),
            samples = output.samples.toList(),
        )
    }

    private fun cueText(cues: List<androidx.media3.common.text.Cue>): String =
        cues.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun copyResource(path: String, dest: File): File {
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
        checkNotNull(stream) { path }
        stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    private class RecordingTextOutput : TextOutput {
        var text: String = ""

        override fun onCues(cueGroup: CueGroup) {
            text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }

        @Suppress("DEPRECATION")
        @Deprecated("CueGroup is the current callback.")
        override fun onCues(cues: List<androidx.media3.common.text.Cue>) = Unit
    }

    private class CueSampleStream(
        private val format: Format,
        private val samples: List<Sample>,
    ) : SampleStream {
        private var formatSent = false
        private var index = 0

        override fun isReady(): Boolean = true

        override fun maybeThrowError() = Unit

        override fun readData(
            formatHolder: FormatHolder,
            buffer: DecoderInputBuffer,
            readFlags: Int,
        ): Int {
            if (!formatSent) {
                formatHolder.format = format
                formatSent = true
                return C.RESULT_FORMAT_READ
            }
            if (index >= samples.size) {
                buffer.clear()
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            val sample = samples[index]
            val omit = readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA != 0
            val peek = readFlags and SampleStream.FLAG_PEEK != 0
            buffer.clear()
            buffer.timeUs = sample.timeUs
            buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            if (!omit) {
                // Leave position at the end of the written bytes. TextRenderer flips.
                buffer.ensureSpaceForWrite(sample.data.size)
                buffer.data!!.put(sample.data)
            }
            if (!peek) index += 1
            return C.RESULT_BUFFER_READ
        }

        override fun skipData(positionUs: Long): Int = 0
    }

    private data class TranscodedText(val format: Format, val samples: List<Sample>)

    private class CollectingTextOutput : ExtractorOutput {
        val samples = mutableListOf<Sample>()
        var format: Format? = null

        override fun track(id: Int, type: Int): TrackOutput {
            if (type != C.TRACK_TYPE_TEXT) return Discard()
            return object : TrackOutput {
                override fun format(format: Format) {
                    this@CollectingTextOutput.format = format
                }

                override fun sampleData(
                    input: androidx.media3.common.DataReader,
                    length: Int,
                    allowEndOfInput: Boolean,
                    sampleDataPart: Int,
                ): Int = error("unused")

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    val bytes = ByteArray(length)
                    data.readBytes(bytes, 0, length)
                    pending = bytes
                }

                private var pending = ByteArray(0)

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) {
                    if (format?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
                        samples += Sample(timeUs, pending.copyOf())
                    }
                }
            }
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class Discard : TrackOutput {
        override fun format(format: Format) = Unit
        override fun sampleData(
            input: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val skip = ByteArray(length)
            return input.read(skip, 0, length)
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit
    }

    private data class Sample(val timeUs: Long, val data: ByteArray)
}
