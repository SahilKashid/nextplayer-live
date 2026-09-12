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
            mimeType == MimeTypes.TEXT_VTT ||
                mimeType.equals("text/vtt", ignoreCase = true) ||
                looksLikeVtt(normalized) -> parseVtt(normalized)
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

    /**
     * WebVTT cue list parser. Tolerates missing `WEBVTT` header, BOM, NOTE/STYLE/REGION
     * blocks, optional hours, cue identifiers, voice spans, and settings after `-->`.
     */
    fun parseVtt(content: String): List<TimedCue> {
        val cues = mutableListOf<TimedCue>()
        val lines = content.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()

        var i = 0
        // Skip file header (WEBVTT …) and leading blanks.
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.startsWith("WEBVTT", ignoreCase = true)) {
                i++
                continue
            }
            break
        }

        while (i < lines.size) {
            if (lines[i].isBlank()) {
                i++
                continue
            }

            // Skip NOTE / STYLE / REGION / X-TIMESTAMP-MAP blocks (through next blank).
            if (isVttNonCueBlockStart(lines[i])) {
                i++
                while (i < lines.size && lines[i].isNotBlank()) i++
                continue
            }

            // Cue block: optional identifier, then timing line, then payload until blank.
            val blockLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                blockLines += lines[i]
                i++
            }
            if (blockLines.isEmpty()) continue

            val timeLineIndex = blockLines.indexOfFirst { VTT_TIME_LINE.containsMatchIn(it) }
            if (timeLineIndex < 0 || timeLineIndex + 1 >= blockLines.size) continue

            val times = parseVttTimeLine(blockLines[timeLineIndex]) ?: continue
            val text = blockLines.subList(timeLineIndex + 1, blockLines.size)
                .joinToString("\n")
                .stripVttTags()
                .trim()
            if (text.isEmpty()) continue
            cues += TimedCue(startMs = times.first, endMs = times.second, text = text)
        }
        return cues
    }

    private fun isVttNonCueBlockStart(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("NOTE", ignoreCase = true) ||
            trimmed.startsWith("STYLE", ignoreCase = true) ||
            trimmed.startsWith("REGION", ignoreCase = true) ||
            trimmed.startsWith("X-TIMESTAMP-MAP", ignoreCase = true)
    }

    private fun looksLikeVtt(content: String): Boolean {
        val trimmed = content.trimStart()
        if (trimmed.startsWith("WEBVTT", ignoreCase = true)) return true
        // Headerless VTT often uses MM:SS.mmm (no hours); SRT always has hours.
        return VTT_TIME_LINE.containsMatchIn(trimmed) &&
            SHORT_VTT_TIME_LINE.containsMatchIn(trimmed)
    }

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
        replace(VTT_VOICE_TAG, "")
            .replace(VTT_TIMESTAMP_TAG, "")
            .replace(HTML_TAG, "")

    private val SRT_TIME_LINE =
        Regex("""(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})""")
    private val VTT_TIME_LINE =
        Regex("""(\d{1,2}:\d{2}(?::\d{2})?[.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}(?::\d{2})?[.]\d{1,3})""")
    /** Matches at least one hours-optional (MM:SS.mmm) timing — distinctive vs SRT. */
    private val SHORT_VTT_TIME_LINE =
        Regex("""(?<!\d)\d{1,2}:\d{2}[.]\d{1,3}\s*-->""")
    private val TIMESTAMP =
        Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val VTT_TIMESTAMP_WITH_HOURS =
        Regex("""(\d{1,2}):(\d{2}):(\d{2})[.](\d{1,3})""")
    private val VTT_TIMESTAMP_SHORT =
        Regex("""(\d{1,2}):(\d{2})[.](\d{1,3})""")
    private val SRT_POSITION_TAG = Regex("""\{\\an\d\}""")
    private val HTML_TAG = Regex("""</?[^>]+>""")
    private val VTT_VOICE_TAG = Regex("""</?v[^>]*>""", RegexOption.IGNORE_CASE)
    private val VTT_TIMESTAMP_TAG = Regex("""<\d{1,2}:\d{2}(?::\d{2})?[.]\d{1,3}>""")
}
