package dev.anilbeesetti.nextplayer.feature.player.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.media.network.datasource.GrowingFileDataSource
import dev.anilbeesetti.nextplayer.core.media.network.datasource.IncompleteLocalMedia
import java.io.File

/**
 * [ExtractorsFactory] that disables Matroska end-of-file cue seeking for local URIs that look
 * **incomplete**.
 *
 * VLC (libmatroska) plays Clusters without requiring Cues at EOF. Media3 [MatroskaExtractor]
 * seeks to the Cues element near EOF when SeekHead points there (ExoPlayer#8935); on incomplete
 * MKVs that seek hits zeros / EOF. With [MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES], playback
 * starts as soon as the header and early clusters are present.
 *
 * Incomplete is **content-based only** ([IncompleteLocalMedia]):
 * - partial filename suffix
 * - SEEK_HOLE tip behind declared length
 * - last ~256KiB all zeros and last-non-zero tip behind declared
 * - optional: declared / readable length growing across a short poll (only if mtime is recent)
 *
 * Finished local MKVs (real cues / non-zero tail, not a partial name) keep cue-seek enabled so
 * seeking is preserved. Directory names are never used.
 *
 * The no-arg [createExtractors] disables cue-seek (unknown URI — safe default). Non-local /
 * network URIs keep cue-seek enabled.
 */
@UnstableApi
class GrowingAwareExtractorsFactory(
    context: Context,
) : ExtractorsFactory {

    private val appContext = context.applicationContext
    private val subtitleParserFactory = DefaultSubtitleParserFactory()

    override fun createExtractors(): Array<Extractor> =
        buildFactory(disableSeekForCues = true).createExtractors()

    override fun createExtractors(
        uri: Uri,
        headers: Map<String, List<String>>,
    ): Array<Extractor> =
        buildFactory(disableSeekForCues = isLikelyGrowing(appContext, uri))
            .createExtractors(uri, headers)

    private fun buildFactory(disableSeekForCues: Boolean): DefaultExtractorsFactory {
        val flags = if (disableSeekForCues) {
            MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES
        } else {
            0
        }
        return DefaultExtractorsFactory()
            .setSubtitleParserFactory(subtitleParserFactory)
            .setMatroskaExtractorFlags(flags)
    }

    companion object {
        private const val LENGTH_POLL_MS = 250L
        /** Only run the optional growth poll when the file was touched this recently. */
        private const val RECENT_MTIME_FOR_POLL_MS = 30_000L

        /**
         * True when a local URI looks incomplete. Network URIs always return false.
         */
        fun isLikelyGrowing(context: Context, uri: Uri): Boolean {
            val scheme = uri.scheme
            val isLocal = scheme.isNullOrEmpty() ||
                ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true) ||
                ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)
            if (!isLocal) return false

            val path = runCatching { context.getPath(uri) }.getOrNull()
                ?: GrowingFileDataSource.resolvePath(uri)
            if (path != null) {
                val file = File(path)
                if (IncompleteLocalMedia.looksPartialFileName(file.name) ||
                    IncompleteLocalMedia.looksPartialFileName(path)
                ) {
                    return true
                }
                if (!file.exists()) return true
                val snapshot = IncompleteLocalMedia.inspect(path)
                if (snapshot.incomplete) return true
                val age = System.currentTimeMillis() - file.lastModified()
                if (age < 0L) return true
                if (age < RECENT_MTIME_FOR_POLL_MS) {
                    return IncompleteLocalMedia.isGrowingAcrossPoll(path, LENGTH_POLL_MS)
                }
                return false
            }

            // content:// without a resolvable path — cannot inspect bytes.
            if (ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)) {
                val displayName = queryDisplayName(context, uri)
                if (IncompleteLocalMedia.looksPartialFileName(displayName) ||
                    IncompleteLocalMedia.looksPartialFileName(uri.lastPathSegment)
                ) {
                    return true
                }
                // Safe default: disable cue-seek when we cannot see the file.
                return true
            }

            return false
        }

        private fun queryDisplayName(context: Context, uri: Uri): String? {
            return try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        uri.lastPathSegment
                    }
                } ?: uri.lastPathSegment
            } catch (_: Exception) {
                uri.lastPathSegment
            }
        }
    }
}
