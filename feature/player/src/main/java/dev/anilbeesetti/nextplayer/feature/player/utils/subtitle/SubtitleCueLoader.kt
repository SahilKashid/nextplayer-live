package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
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
 * [EmbeddedSubtitleCueExtractor] with progressive partial updates and optional
 * near-playback-first seeking. MediaController player APIs stay on Main.
 *
 * Results are cached in-memory (session LRU) and on disk under `subtitleCacheDir`.
 */
@UnstableApi
object SubtitleCueLoader {

    private val media3SubtitleParserFactory = DefaultSubtitleParserFactory()

    data class LoadResult(
        val cues: List<TimedCue>,
        val cacheKey: String,
        val fromCache: Boolean,
        /**
         * True only for image-based subtitle formats (PGS / VobSub / DVB). Empty text
         * loads must keep this false so the panel shows the empty-cues message rather
         * than the unsupported bitmap message.
         */
        val isUnsupportedBitmapTrack: Boolean = false,
    )

    suspend fun loadSelectedTrackCues(context: Context, player: Player): List<TimedCue> =
        loadSelectedTrackCuesDetailed(context, player).cues

    suspend fun loadSelectedTrackCuesDetailed(
        context: Context,
        player: Player,
        onPartialCues: ((List<TimedCue>) -> Unit)? = null,
    ): LoadResult {
        val selection = withContext(Dispatchers.Main.immediate) {
            resolveSelectedSubtitle(player)
        } ?: return LoadResult(emptyList(), "none", fromCache = false)

        val mediaId = withContext(Dispatchers.Main.immediate) {
            player.currentMediaItem?.mediaId
        }
        val playbackPositionMs = withContext(Dispatchers.Main.immediate) {
            player.currentPosition.coerceAtLeast(0L)
        }
        val cacheKey = LiveSubtitleCueCache.buildKey(
            mediaId = mediaId,
            mediaUri = selection.mediaUri,
            externalUri = selection.externalUri,
            format = selection.format,
            textTrackIndex = selection.textTrackIndex,
            context = context,
        )
        val isBitmap =
            selection.format != null &&
                EmbeddedSubtitleCueExtractor.isBitmapSubtitle(selection.format)

        // Image tracks cannot become text cues — skip demux/cache entirely.
        if (isBitmap) {
            return LoadResult(
                cues = emptyList(),
                cacheKey = cacheKey,
                fromCache = false,
                isUnsupportedBitmapTrack = true,
            )
        }

        // Memory hit: paint immediately, no spinner.
        LiveSubtitleCueCache.getMemoryOnly(cacheKey)?.let {
            return LoadResult(it, cacheKey, fromCache = true, isUnsupportedBitmapTrack = false)
        }

        val diskHit = withContext(Dispatchers.IO) {
            LiveSubtitleCueCache.get(context, cacheKey)
        }
        if (diskHit != null) {
            return LoadResult(diskHit, cacheKey, fromCache = true, isUnsupportedBitmapTrack = false)
        }

        val loaded = withContext(Dispatchers.IO) {
            when {
                selection.externalUri != null ->
                    loadExternalCues(context, selection.externalUri, selection.format)
                selection.mediaUri != null && selection.format != null -> {
                    EmbeddedSubtitleCueExtractor.extract(
                        context = context,
                        mediaUri = selection.mediaUri,
                        selectedFormat = selection.format,
                        preferredTextTrackIndex = selection.textTrackIndex,
                        playbackPositionMs = playbackPositionMs,
                        onPartialCues = onPartialCues,
                    )
                }
                else -> emptyList()
            }
        }
        if (loaded.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                LiveSubtitleCueCache.put(context, cacheKey, loaded)
            }
        }
        return LoadResult(
            cues = loaded,
            cacheKey = cacheKey,
            fromCache = false,
            isUnsupportedBitmapTrack = false,
        )
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
        val configs = player.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty()
        val externalUri = resolveExternalSubtitleUri(format, configs)
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

    /**
     * Maps the selected player [Format] to a sideloaded [MediaItem.SubtitleConfiguration] URI.
     *
     * Media3 often exposes sideloaded tracks as [MimeTypes.APPLICATION_MEDIA3_CUES] with the
     * original mime in [Format.codecs]. Matching must use that original mime — otherwise a lone
     * external WebVTT (.vtt) track is missed, the loader demuxes the video container instead, and
     * the live panel stays empty while the on-video overlay still works.
     */
    internal fun resolveExternalSubtitleUri(
        format: Format,
        configs: List<MediaItem.SubtitleConfiguration>,
    ): Uri? {
        if (configs.isEmpty()) return null

        val formatId = format.id
        val label = format.label
        val originalMime = EmbeddedSubtitleCueExtractor.originalSubtitleMime(format)

        // External subs set SubtitleConfiguration.id to the source URI string.
        if (!formatId.isNullOrBlank()) {
            configs.firstOrNull { it.id == formatId }?.uri?.let { return it }
            configs.firstOrNull { it.uri.toString() == formatId }?.uri?.let { return it }
            runCatching { Uri.parse(formatId) }
                .getOrNull()
                ?.takeIf { it.scheme != null }
                ?.let { parsed ->
                    // Only accept if it matches a known configuration (avoid treating
                    // embedded numeric ids as file URIs).
                    if (configs.any { it.id == formatId || it.uri == parsed || it.uri.toString() == formatId }) {
                        return configs.firstOrNull { it.id == formatId }?.uri
                            ?: configs.firstOrNull { it.uri == parsed || it.uri.toString() == formatId }?.uri
                            ?: parsed
                    }
                }
        }

        if (!label.isNullOrBlank()) {
            configs.firstOrNull { it.label == label }?.uri?.let { return it }
        }

        // Unique mime match (e.g. codecs=text/vtt vs configuration mimeType=text/vtt).
        if (!originalMime.isNullOrBlank()) {
            val mimeMatches = configs.filter { configurationMimeMatches(it.mimeType, originalMime) }
            if (mimeMatches.size == 1) return mimeMatches.first().uri
        }

        // Single external configuration: only treat it as selected when the
        // player format looks like that external track (not an embedded one).
        if (configs.size == 1) {
            val only = configs.first()
            val looksExternal =
                (!formatId.isNullOrBlank() && (formatId == only.id || formatId == only.uri.toString())) ||
                    (!label.isNullOrBlank() && label == only.label) ||
                    configurationMimeMatches(only.mimeType, format.sampleMimeType) ||
                    configurationMimeMatches(only.mimeType, originalMime) ||
                    configurationMimeMatches(only.mimeType, format.codecs)
            if (looksExternal) return only.uri
        }
        return null
    }

    private fun configurationMimeMatches(configMime: String?, formatMime: String?): Boolean {
        if (configMime.isNullOrBlank() || formatMime.isNullOrBlank()) return false
        if (formatMime == MimeTypes.APPLICATION_MEDIA3_CUES) return false
        return configMime.equals(formatMime, ignoreCase = true)
    }

    internal fun loadExternalCues(
        context: Context,
        uri: Uri,
        format: Format? = null,
    ): List<TimedCue> {
        val bytes = readBytes(context, uri) ?: return emptyList()
        val mimeType = resolveExternalMime(uri, format)

        if (isLegacyParsedMime(mimeType) || mimeType.equals("text/vtt", ignoreCase = true)) {
            val content = decodeSubtitleBytes(bytes)
            val parsed = SubtitleCueParser.parse(content, mimeType)
            if (parsed.isNotEmpty()) return parsed
        }

        // Content sniff: URI/path may have lost the .vtt extension (content://).
        val sniffed = SubtitleCueParser.parse(decodeSubtitleBytes(bytes), mimeType = null)
        if (sniffed.isNotEmpty()) return sniffed

        // ASS/TTML/others (and SRT/VTT fallback) via Media3 subtitle parsers.
        val media3 = parseWithMedia3(bytes, mimeType)
        if (media3.isNotEmpty()) return media3

        // Last resort: try Media3 as WebVTT when format/codecs say VTT but URI mime was wrong.
        if (mimeType != MimeTypes.TEXT_VTT) {
            val asVtt = parseWithMedia3(bytes, MimeTypes.TEXT_VTT)
            if (asVtt.isNotEmpty()) return asVtt
        }
        return emptyList()
    }

    private fun resolveExternalMime(uri: Uri, format: Format?): String {
        val uriMime = uri.getSubtitleMime()
        val original = format?.let { EmbeddedSubtitleCueExtractor.originalSubtitleMime(it) }
        return when {
            uriMime == MimeTypes.TEXT_VTT -> MimeTypes.TEXT_VTT
            original == MimeTypes.TEXT_VTT ||
                original.equals("text/vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
            format?.sampleMimeType == MimeTypes.TEXT_VTT -> MimeTypes.TEXT_VTT
            // Prefer non-default URI mime; otherwise fall back to format original mime.
            uriMime != MimeTypes.APPLICATION_SUBRIP -> uriMime
            !original.isNullOrBlank() && original != MimeTypes.APPLICATION_MEDIA3_CUES -> original
            else -> uriMime
        }
    }

    private fun isLegacyParsedMime(mimeType: String): Boolean =
        mimeType == MimeTypes.APPLICATION_SUBRIP ||
            mimeType == MimeTypes.TEXT_VTT ||
            mimeType.equals("text/vtt", ignoreCase = true)

    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        // Strip UTF-8 BOM; also tolerate UTF-16 LE/BE BOM for sideloaded files.
        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() ->
                bytes.toString(Charset.forName("UTF-8")).removePrefix("\uFEFF")
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                bytes.toString(Charset.forName("UTF-16LE")).removePrefix("\uFEFF")
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                bytes.toString(Charset.forName("UTF-16BE")).removePrefix("\uFEFF")
            else -> bytes.toString(Charset.forName("UTF-8")).removePrefix("\uFEFF")
        }
    }

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
