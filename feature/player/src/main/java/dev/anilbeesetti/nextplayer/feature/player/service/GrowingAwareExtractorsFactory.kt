package dev.anilbeesetti.nextplayer.feature.player.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.media.network.datasource.GrowingFileDataSource
import dev.anilbeesetti.nextplayer.core.media.network.datasource.IncompleteLocalMedia
import dev.anilbeesetti.nextplayer.core.media.network.datasource.ReadableTipTracker

/**
 * [ExtractorsFactory] that disables Matroska end-of-file cue seeking for local URIs that look
 * **incomplete**, and wraps Matroska extractors with [IncompleteMatroskaSeekExtractor] so the
 * player still gets an EBML-validated Cluster [androidx.media3.extractor.IndexSeekMap]
 * within the downloaded tip (or stays unseekable if too few Clusters validate).
 *
 * VLC (libmatroska) plays Clusters without requiring Cues at EOF. Media3 [MatroskaExtractor]
 * seeks to the Cues element near EOF when SeekHead points there (ExoPlayer#8935); on incomplete
 * MKVs that seek hits zeros / EOF. With [MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES], playback
 * starts as soon as the header and early clusters are present. The wrapper then replaces the
 * resulting [androidx.media3.extractor.SeekMap.Unseekable] with an indexed Cluster seek map
 * and snaps seek byte positions to those Cluster starts.
 *
 * Incomplete is **content-based only** ([IncompleteLocalMedia]):
 * - partial filename suffix
 * - SEEK_HOLE tip behind declared length (a far hole is ignored when the tail
 *   already has real cues / media — finished file)
 * - last ~256KiB all zeros and last-non-zero tip behind declared
 * - optional: declared / readable length growing across a short poll (only if mtime is recent)
 *
 * Finished local MKVs (real cues / non-zero tail, not a partial name, mtime not growing)
 * keep cue-seek enabled and are **not** wrapped, so seeking is unchanged — including
 * finished `content://` Open-with URIs inspected via AFD/PFD. Directory names are never used.
 *
 * The no-arg [createExtractors] disables cue-seek (unknown URI — safe default) but does not wrap
 * (no tip key). Non-local / network URIs keep cue-seek enabled.
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
    ): Array<Extractor> {
        val growing = isLikelyGrowing(appContext, uri)
        val extractors = buildFactory(disableSeekForCues = growing)
            .createExtractors(uri, headers)
        if (!growing) return extractors

        val path = resolveLocalPath(appContext, uri)
        val tipKey = path ?: uri.toString()
        val pathProvider: () -> String? = { path ?: resolveLocalPath(appContext, uri) }

        return Array(extractors.size) { index ->
            val extractor = extractors[index]
            if (isMatroskaExtractor(extractor)) {
                IncompleteMatroskaSeekExtractor(
                    delegate = extractor,
                    tipProvider = { tipForKey(tipKey, path) },
                    filePathProvider = pathProvider,
                )
            } else {
                extractor
            }
        }
    }

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
        /**
         * True when a local URI looks incomplete. Network URIs always return false.
         */
        fun isLikelyGrowing(context: Context, uri: Uri): Boolean {
            val scheme = uri.scheme
            val isLocal = scheme.isNullOrEmpty() ||
                ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true) ||
                ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)
            if (!isLocal) return false

            val path = resolveLocalPath(context, uri)
            if (path != null) {
                return IncompleteLocalMedia.shouldPlayAsGrowing(path)
            }

            // content:// without a resolvable path — inspect via AFD/PFD (Open-with).
            if (ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)) {
                return IncompleteLocalMedia.shouldPlayAsGrowing(context, uri)
            }

            return false
        }

        fun resolveLocalPath(context: Context, uri: Uri): String? {
            return runCatching { context.getPath(uri) }.getOrNull()
                ?: GrowingFileDataSource.resolvePath(uri)
        }

        fun isMatroskaExtractor(extractor: Extractor): Boolean {
            var current: Extractor? = extractor
            var depth = 0
            while (current != null && depth < 8) {
                if (current is MatroskaExtractor) return true
                val name = current.javaClass.name
                if (name.contains("MatroskaExtractor", ignoreCase = false)) return true
                val next = current.underlyingImplementation
                if (next === current) break
                current = next
                depth++
            }
            return false
        }

        private fun tipLookupKeys(tipKey: String, path: String?): List<String> {
            val keys = LinkedHashSet<String>()
            keys.add(tipKey)
            if (path != null) {
                keys.add(path)
                if (path.startsWith("/")) keys.add("file://$path")
            }
            if (tipKey.startsWith("/")) {
                keys.add("file://$tipKey")
            } else if (tipKey.startsWith("file://")) {
                val stripped = tipKey.removePrefix("file://")
                if (stripped.isNotEmpty()) keys.add(stripped)
            }
            return keys.toList()
        }

        private fun tipForKey(tipKey: String, path: String?): Long {
            for (key in tipLookupKeys(tipKey, path)) {
                val tracked = ReadableTipTracker.tipFor(key)
                if (tracked > 0L) return tracked
            }
            if (path != null) {
                val snap = IncompleteLocalMedia.inspect(path)
                if (snap.readableEnd > 0L) {
                    for (key in tipLookupKeys(tipKey, path)) {
                        ReadableTipTracker.update(key, snap.readableEnd, snap.declaredLength)
                    }
                    return snap.readableEnd
                }
            }
            return -1L
        }

    }
}
