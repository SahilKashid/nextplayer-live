package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import dev.anilbeesetti.nextplayer.feature.player.extensions.getSubtitleMime
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a full cue timeline for the currently selected subtitle track.
 *
 * External SRT/VTT (and other Media3-parseable text files) are read from their URI.
 * Embedded in-container text tracks are demuxed on a background thread via
 * [EmbeddedSubtitleCueExtractor]. MediaController player APIs stay on Main.
 */
@UnstableApi
object SubtitleCueLoader {

    private val media3SubtitleParserFactory = DefaultSubtitleParserFactory()

    suspend fun loadSelectedTrackCues(context: Context, player: Player): List<TimedCue> {
        val selection = withContext(Dispatchers.Main.immediate) {
            resolveSelectedSubtitle(player)
        } ?: return emptyList()

        return withContext(Dispatchers.IO) {
            when {
                selection.externalUri != null -> loadExternalCues(context, selection.externalUri)
                selection.mediaUri != null && selection.format != null -> {
                    EmbeddedSubtitleCueExtractor.extract(
                        context = context,
                        mediaUri = selection.mediaUri,
                        selectedFormat = selection.format,
                        preferredTextTrackIndex = selection.textTrackIndex,
                    )
                }
                else -> emptyList()
            }
        }
    }

    /**
     * Snapshot of the selected text track, gathered on the player application thread.
     */
    data class SelectedSubtitle(
        val externalUri: Uri?,
        val mediaUri: Uri?,
        val format: Format?,
        val textTrackIndex: Int,
    )

    fun resolveSelectedSubtitle(player: Player): SelectedSubtitle? {
        val textGroups = player.currentTracks.groups.filter {
            it.type == C.TRACK_TYPE_TEXT && it.isSupported
        }
        val selectedGroup = textGroups.firstOrNull { it.isSelected } ?: return null
        val format = selectedGroup.getTrackFormat(0)
        val textTrackIndex = textGroups.indexOf(selectedGroup).coerceAtLeast(0)
        val mediaUri = player.currentMediaItem?.localConfiguration?.uri
        val externalUri = resolveExternalSubtitleUri(player, format)
        return SelectedSubtitle(
            externalUri = externalUri,
            mediaUri = mediaUri,
            format = format,
            textTrackIndex = textTrackIndex,
        )
    }

    /** @deprecated Prefer [resolveSelectedSubtitle]; kept for call-site clarity in tests. */
    fun resolveSelectedSubtitleUri(player: Player): Uri? =
        resolveSelectedSubtitle(player)?.externalUri

    private fun resolveExternalSubtitleUri(player: Player, format: Format): Uri? {
        val formatId = format.id
        val configs = player.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty()

        // External subs set SubtitleConfiguration.id to the source URI string.
        if (!formatId.isNullOrBlank()) {
            configs.firstOrNull { it.id == formatId }?.uri?.let { return it }
            runCatching { Uri.parse(formatId) }
                .getOrNull()
                ?.takeIf { it.scheme != null }
                ?.let { return it }
        }

        val label = format.label
        if (!label.isNullOrBlank()) {
            configs.firstOrNull { it.label == label }?.uri?.let { return it }
        }

        // Single external configuration and one selected text track — use it.
        if (configs.size == 1) {
            return configs.first().uri
        }
        return null
    }

    private fun loadExternalCues(context: Context, uri: Uri): List<TimedCue> {
        val mimeType = uri.getSubtitleMime()
        val bytes = readBytes(context, uri) ?: return emptyList()

        if (isLegacyParsedMime(mimeType)) {
            val content = bytes.toString(Charset.forName("UTF-8"))
            val parsed = SubtitleCueParser.parse(content, mimeType)
            if (parsed.isNotEmpty()) return parsed
        }

        // ASS/TTML/others (and SRT/VTT fallback) via Media3 subtitle parsers.
        return parseWithMedia3(bytes, mimeType)
    }

    private fun isLegacyParsedMime(mimeType: String): Boolean =
        mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == MimeTypes.TEXT_VTT

    private fun parseWithMedia3(bytes: ByteArray, mimeType: String): List<TimedCue> {
        val format = Format.Builder().setSampleMimeType(mimeType).build()
        if (!media3SubtitleParserFactory.supportsFormat(format)) return emptyList()
        val parser = media3SubtitleParserFactory.create(format)
        val parsed = mutableListOf<TimedCue>()
        val seen = HashSet<String>()
        runCatching {
            parser.parse(
                bytes,
                0,
                bytes.size,
                SubtitleParser.OutputOptions.allCues(),
            ) { cuesWithTiming ->
                for (timed in EmbeddedSubtitleCueExtractor.cuesWithTimingToTimedCues(cuesWithTiming)) {
                    val key = "${timed.startMs}|${timed.endMs}|${timed.text}"
                    if (seen.add(key)) parsed += timed
                }
            }
        }
        parser.reset()
        return parsed.sortedBy { it.startMs }
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: uri.path?.let { path ->
                java.io.File(path).takeIf { it.exists() && it.isFile }?.readBytes()
            }
        }.getOrNull()
    }
}
