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
import dev.anilbeesetti.nextplayer.core.media.network.datasource.GrowingContentDataSource
import dev.anilbeesetti.nextplayer.core.media.network.datasource.GrowingFileDataSource
import java.io.File

/**
 * [ExtractorsFactory] that disables Matroska end-of-file cue seeking for local URIs unless the
 * file is clearly a finished download.
 *
 * VLC (libmatroska) plays Clusters without requiring Cues at EOF. Media3 [MatroskaExtractor]
 * seeks to the Cues element near EOF when SeekHead points there (ExoPlayer#8935); on incomplete
 * MKVs that seek hits EOF / fails while the file is still growing. With
 * [MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES], playback can start as soon as the header and
 * early clusters are present — matching VLC-style play-while-download (unseekable until the file
 * finishes / cues become available).
 *
 * For local `file://` / `content://` / path URIs we **default to disabling cue-seek** whenever
 * the file might still be downloading. Cue-seek stays enabled only when the file is clearly
 * finished (exists, non-partial name, mtime age ≥ 5 minutes, and a short length poll shows no
 * growth). Missing files, partial suffixes, and unresolved `content://` URIs always disable cues.
 */
@UnstableApi
class GrowingAwareExtractorsFactory(
    context: Context,
) : ExtractorsFactory {

    private val appContext = context.applicationContext
    private val subtitleParserFactory = DefaultSubtitleParserFactory()

    override fun createExtractors(): Array<Extractor> =
        buildFactory(disableSeekForCues = false).createExtractors()

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
        /** Only treat as finished after this mtime age (plus a no-growth poll). */
        private const val CLEARLY_FINISHED_MTIME_MS = 300_000L
        private const val LENGTH_POLL_MS = 250L

        /**
         * VLC-like default: assume a local file may still be downloading and disable cue-seek,
         * unless it is clearly finished (old mtime + stable length + non-partial name).
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
                if (GrowingFileDataSource.looksPartialFileName(file.name) ||
                    GrowingFileDataSource.looksPartialFileName(path)
                ) {
                    return true
                }
                // Missing file → still may appear; keep cues disabled.
                if (!file.exists()) return true
                val now = System.currentTimeMillis()
                val age = now - file.lastModified()
                // Clock skew / future mtime — treat as growing.
                if (age < 0L) return true
                // Not clearly finished until mtime is old enough.
                if (age < CLEARLY_FINISHED_MTIME_MS) {
                    return true
                }
                // Age ≥ 5 minutes: confirm length is not still growing.
                val length1 = file.length()
                try {
                    Thread.sleep(LENGTH_POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return true
                }
                return file.length() > length1
            }

            // content:// without resolvable path — always disable cue-seek (may be downloading).
            if (ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)) {
                val displayName = queryDisplayName(context, uri)
                if (GrowingContentDataSource.looksPartialName(displayName) ||
                    GrowingContentDataSource.looksPartialName(uri.lastPathSegment)
                ) {
                    return true
                }
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
