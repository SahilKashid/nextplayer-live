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
 * [ExtractorsFactory] that disables Matroska end-of-file cue seeking when the URI looks like a
 * still-growing local download.
 *
 * Media3 [MatroskaExtractor] seeks to the Cues element near EOF when SeekHead points there.
 * On incomplete MKVs that seek hits EOF / fails while the file is still growing. With
 * [MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES], playback can start as soon as the header and
 * early clusters are present — without a long demux wait — matching VLC-style
 * play-while-download (unseekable until the file finishes / cues become available).
 *
 * Finished MKVs keep default flags (0) so cue-based seeking still works.
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
        private const val RECENT_MTIME_MS = 90_000L
        private const val VERY_FRESH_MTIME_MS = 20_000L
        private const val LENGTH_POLL_MS = 175L

        /**
         * Heuristics for play-while-download: partial suffixes, recent mtime, growing length,
         * or unresolved content:// downloads (prefer cue-seek disable when unsure).
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
                if (!file.exists()) return false
                val now = System.currentTimeMillis()
                val age = now - file.lastModified()
                if (age < 0L) return false
                if (age < VERY_FRESH_MTIME_MS) {
                    return true
                }
                if (age < RECENT_MTIME_MS) {
                    val length1 = file.length()
                    try {
                        Thread.sleep(LENGTH_POLL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return true
                    }
                    return file.length() > length1
                }
                return false
            }

            // content:// without resolvable path — prefer FLAG_DISABLE_SEEK_FOR_CUES when unsure.
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
