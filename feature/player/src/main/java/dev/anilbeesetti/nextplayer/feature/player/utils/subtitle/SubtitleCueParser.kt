package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import androidx.media3.common.MimeTypes
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue

/**
 * Parses common external subtitle formats into a full cue timeline.
 *
 * Supported: SubRip (SRT) and WebVTT. Other formats return an empty list.
 */
object SubtitleCueParser {

    fun parse(content: String, mimeType: String?): List<TimedCue> {
        val normalized = content.removePrefix("\uFEFF")
        return when {
            mimeType == MimeTypes.TEXT_VTT || looksLikeVtt(normalized) -> parseVtt(normalized)
            mimeType == MimeTypes.APPLICATION_SUBRIP || looksLikeSrt(normalized) -> parseSrt(normalized)
            mimeType.isNullOrBlank() && looksLikeVtt(normalized) -> parseVtt(normalized)
            mimeType.isNullOrBlank() && looksLikeSrt(normalized) -> parseSrt(normalized)
            else -> emptyList()
        }
    }

    fun parseSrt(content: String): List<TimedCue> {
        val cues = mutableListOf<TimedCue>()
        val blocks = content.replace("\r\n", "\n").replace('\r', '\n').split("\n\n")
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size < 2) continue

            val timeLineIndex = lines.indexOfFirst { SRT_TIME_LINE.containsMatchIn(it) }
            if (timeLineIndex < 0 || timeLineIndex + 1 >= lines.size) continue

            val times = parseSrtTimeLine(lines[timeLineIndex]) ?: continue
            val text = lines.subList(timeLineIndex + 1, lines.size)
                .joinToString("\n")
                .stripSrtTags()
                .trim()
            if (text.isEmpty()) continue
            cues += TimedCue(startMs = times.first, endMs = times.second, text = text)
        }
        return cues
    }

    fun parseVtt(content: String): List<TimedCue> {
        val cues = mutableListOf<TimedCue>()
        val body = content.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence()
            .dropWhile { line ->
                line.isBlank() ||
                    line.startsWith("WEBVTT", ignoreCase = true) ||
                    line.startsWith("NOTE", ignoreCase = true) ||
                    line.startsWith("STYLE", ignoreCase = true) ||
                    line.startsWith("REGION", ignoreCase = true) ||
                    line.startsWith("X-TIMESTAMP-MAP", ignoreCase = true)
            }
            .joinToString("\n")

        val blocks = body.split("\n\n")
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() && !it.startsWith("NOTE", ignoreCase = true) }
            if (lines.isEmpty()) continue

            val timeLineIndex = lines.indexOfFirst { VTT_TIME_LINE.containsMatchIn(it) }
            if (timeLineIndex < 0 || timeLineIndex + 1 >= lines.size) continue

            val times = parseVttTimeLine(lines[timeLineIndex]) ?: continue
            val text = lines.subList(timeLineIndex + 1, lines.size)
                .joinToString("\n")
                .stripVttTags()
                .trim()
            if (text.isEmpty()) continue
            cues += TimedCue(startMs = times.first, endMs = times.second, text = text)
        }
        return cues
    }

    private fun looksLikeVtt(content: String): Boolean =
        content.trimStart().startsWith("WEBVTT", ignoreCase = true)

    private fun looksLikeSrt(content: String): Boolean =
        SRT_TIME_LINE.containsMatchIn(content)

    private fun parseSrtTimeLine(line: String): Pair<Long, Long>? {
        val match = SRT_TIME_LINE.find(line) ?: return null
        val start = parseSrtTimestamp(match.groupValues[1]) ?: return null
        val end = parseSrtTimestamp(match.groupValues[2]) ?: return null
        return start to end
    }

    private fun parseVttTimeLine(line: String): Pair<Long, Long>? {
        val match = VTT_TIME_LINE.find(line) ?: return null
        val start = parseVttTimestamp(match.groupValues[1]) ?: return null
        val end = parseVttTimestamp(match.groupValues[2]) ?: return null
        return start to end
    }

    /**
     * SRT timestamps use a comma for milliseconds: 00:00:01,000
     */
    internal fun parseSrtTimestamp(value: String): Long? {
        val match = TIMESTAMP.find(value.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val fraction = match.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: return null
        return ((hours * 3600) + (minutes * 60) + seconds) * 1000 + fraction
    }

    /**
     * VTT timestamps use a dot for milliseconds and hours are optional: 00:01.000 or 00:00:01.000
     */
    internal fun parseVttTimestamp(value: String): Long? {
        val trimmed = value.trim()
        val withHours = VTT_TIMESTAMP_WITH_HOURS.find(trimmed)
        if (withHours != null) {
            val hours = withHours.groupValues[1].toLongOrNull() ?: return null
            val minutes = withHours.groupValues[2].toLongOrNull() ?: return null
            val seconds = withHours.groupValues[3].toLongOrNull() ?: return null
            val fraction = withHours.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: return null
            return ((hours * 3600) + (minutes * 60) + seconds) * 1000 + fraction
        }
        val short = VTT_TIMESTAMP_SHORT.find(trimmed) ?: return null
        val minutes = short.groupValues[1].toLongOrNull() ?: return null
        val seconds = short.groupValues[2].toLongOrNull() ?: return null
        val fraction = short.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: return null
        return ((minutes * 60) + seconds) * 1000 + fraction
    }

    private fun String.stripSrtTags(): String =
        replace(HTML_TAG, "")
            .replace(SRT_POSITION_TAG, "")

    private fun String.stripVttTags(): String =
        replace(HTML_TAG, "")
            .replace(VTT_VOICE_TAG, "")
            .replace(VTT_TIMESTAMP_TAG, "")

    private val SRT_TIME_LINE =
        Regex("""(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})""")
    private val VTT_TIME_LINE =
        Regex("""(\d{1,2}:\d{2}(?::\d{2})?[.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}(?::\d{2})?[.]\d{1,3})""")
    private val TIMESTAMP =
        Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val VTT_TIMESTAMP_WITH_HOURS =
        Regex("""(\d{1,2}):(\d{2}):(\d{2})[.](\d{1,3})""")
    private val VTT_TIMESTAMP_SHORT =
        Regex("""(\d{1,2}):(\d{2})[.](\d{1,3})""")
    private val SRT_POSITION_TAG = Regex("""\{\\an\d\}""")
    private val HTML_TAG = Regex("""</?[^>]+>""")
    private val VTT_VOICE_TAG = Regex("""<v[^>]*>""", RegexOption.IGNORE_CASE)
    private val VTT_TIMESTAMP_TAG = Regex("""<\d{1,2}:\d{2}(?::\d{2})?[.]\d{1,3}>""")
}
