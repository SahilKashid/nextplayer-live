package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.feature.player.extensions.getSubtitleMime
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a full cue timeline for the currently selected external subtitle track.
 *
 * Embedded (in-container) text tracks do not expose a parseable URI; those return empty.
 * MediaController player APIs must run on the application thread; file I/O stays on IO.
 */
@UnstableApi
object SubtitleCueLoader {

    suspend fun loadSelectedTrackCues(context: Context, player: Player): List<TimedCue> {
        val uri = withContext(Dispatchers.Main.immediate) {
            resolveSelectedSubtitleUri(player)
        } ?: return emptyList()

        return withContext(Dispatchers.IO) {
            val mimeType = uri.getSubtitleMime()
            if (!isSupportedMime(mimeType)) return@withContext emptyList()
            val content = readText(context, uri) ?: return@withContext emptyList()
            SubtitleCueParser.parse(content, mimeType)
        }
    }

    fun resolveSelectedSubtitleUri(player: Player): Uri? {
        val selectedGroup = player.currentTracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_TEXT && it.isSelected && it.isSupported
        } ?: return null

        val format = selectedGroup.getTrackFormat(0)
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

    private fun isSupportedMime(mimeType: String): Boolean =
        mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == MimeTypes.TEXT_VTT

    private fun readText(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charset.forName("UTF-8"))
            } ?: uri.path?.let { path ->
                java.io.File(path).takeIf { it.exists() && it.isFile }?.readText(Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
