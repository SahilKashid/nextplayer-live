package dev.anilbeesetti.nextplayer.feature.player.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.media.network.datasource.DownloadPathHeuristic
import dev.anilbeesetti.nextplayer.core.media.network.datasource.GrowingContentDataSource
import dev.anilbeesetti.nextplayer.core.media.network.datasource.SparseAwareFileLength
import dev.anilbeesetti.nextplayer.core.media.network.datasource.GrowingFileDataSource
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * Brief retries for progressive load/source errors on local growing files
 * (`file://` / `content://`) while headers are still missing or incomplete.
 *
 * MKV play-while-download is handled by [GrowingAwareExtractorsFactory]
 * ([androidx.media3.extractor.mkv.MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES]) so
 * playback can start as soon as the header and early clusters are present — this policy
 * does **not** introduce a long demux delay.
 *
 * This policy only covers short waits for tiny/incomplete headers, for example:
 * - empty or near-empty file at open
 * - rare MP4/MOV where `moov` has not landed yet
 * - transient parse / EOF / IO while bytes are still arriving on a still-tiny or
 *   actively growing / partial download
 *
 * [DefaultLoadErrorHandlingPolicy] treats [ParserException] /
 * [UnrecognizedInputFormatException] as non-retriable; without a short retry those
 * surface immediately as "Source error" / "Can't play video" when the file is opened
 * before any usable header bytes exist.
 *
 * Ordinary caps are short (~14 attempts). Download-manager paths, zero-padded tails, and
 * Matroska varint/EBML errors get a longer budget (~50 × ~600ms ≈ 30s) so play-while-download
 * survives stale mtime / full declared length. Large + stable + non-download still fail fast.
 */
@UnstableApi
class GrowingFileLoadErrorHandlingPolicy(
    context: Context,
    private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
) : LoadErrorHandlingPolicy by delegate {

    private val appContext = context.applicationContext

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (shouldRetryGrowingLocal(loadErrorInfo)) {
            return RETRY_DELAY_MS
        }
        return delegate.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return maxOf(
            delegate.getMinimumLoadableRetryCount(dataType),
            MIN_GROWING_LOADABLE_RETRIES,
        )
    }

    private fun shouldRetryGrowingLocal(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Boolean {
        val uri = loadErrorInfo.loadEventInfo.uri
            ?: loadErrorInfo.loadEventInfo.dataSpec.uri
            ?: (loadErrorInfo.exception as? UnrecognizedInputFormatException)?.uri
            ?: return false
        if (!isLocalUri(uri)) return false
        if (!isRetriableWhileGrowing(loadErrorInfo.exception)) return false
        val downloadLike = isDownloadManagerLike(uri)
        val varintLike = isVarintOrEbmlParseError(loadErrorInfo.exception)
        val maxRetries = if (downloadLike || varintLike) {
            MAX_DOWNLOAD_PATH_RETRIES
        } else {
            MAX_GROWING_RETRIES
        }
        if (loadErrorInfo.errorCount >= maxRetries) return false
        return appearsStillGrowingOrIncomplete(uri, downloadLike = downloadLike, varintLike = varintLike)
    }

    private fun isLocalUri(uri: Uri): Boolean {
        val scheme = uri.scheme
        return scheme.isNullOrEmpty() ||
            ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true) ||
            ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)
    }

    /**
     * Retry while the file is still tiny, actively growing, download-manager-like, has a
     * zero-padded / sparse tip behind declared length, or the error looks like Matroska
     * reading into zeros (`No valid varint length mask`). Large + stable + non-download → no.
     */
    private fun appearsStillGrowingOrIncomplete(
        uri: Uri,
        downloadLike: Boolean,
        varintLike: Boolean,
    ): Boolean {
        val path = runCatching { appContext.getPath(uri) }.getOrNull()
            ?: GrowingFileDataSource.resolvePath(uri)
        if (path != null) {
            val file = File(path)
            if (downloadLike ||
                GrowingFileDataSource.looksPartialFileName(file.name) ||
                GrowingFileDataSource.looksPartialFileName(path) ||
                DownloadPathHeuristic.looksLikeDownloadManagerPath(path)
            ) {
                return tipStillAdvancingOrAllowRetry(file, path, force = true)
            }
            if (!file.exists()) return true
            val preferZero = DownloadPathHeuristic.looksLikeDownloadManagerPath(path)
            val declared1 = file.length()
            val tip1 = SparseAwareFileLength.readableEnd(
                path,
                declared1,
                fd = null,
                preferZeroTailScan = preferZero,
            )
            if (SparseAwareFileLength.isSparsePartial(declared1, tip1)) {
                return tipStillAdvancingOrAllowRetry(file, path, force = true)
            }
            if (declared1 < SNIFF_READY_BYTES) {
                return true
            }
            if (varintLike) {
                // Varint/EBML errors on a large "stable" file often mean we read into a zero
                // tail that SEEK_HOLE missed — keep retrying briefly while tip may advance.
                return tipStillAdvancingOrAllowRetry(file, path, force = true)
            }
            try {
                Thread.sleep(STABLE_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return true
            }
            val declared2 = file.length()
            val tip2 = SparseAwareFileLength.readableEnd(
                path,
                declared2,
                fd = null,
                preferZeroTailScan = preferZero,
            )
            if (SparseAwareFileLength.isSparsePartial(declared2, tip2)) return true
            return tip2 > tip1 || declared2 > declared1
        }

        if (ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) {
            val displayName = queryDisplayName(uri)
            if (downloadLike ||
                varintLike ||
                GrowingContentDataSource.looksPartialName(displayName) ||
                GrowingContentDataSource.looksPartialName(uri.lastPathSegment) ||
                DownloadPathHeuristic.looksIncompleteDownload(uri.toString(), displayName)
            ) {
                return true
            }
            val size = queryContentSize(uri)
            if (size != null && size < SNIFF_READY_BYTES) {
                return true
            }
            return false
        }
        return false
    }

    /**
     * For download-manager / zero-tail / varint cases: keep retrying while the tip advances;
     * if the tip is stuck we still return true so the attempt budget ([MAX_DOWNLOAD_PATH_RETRIES])
     * provides the time bound (~30s).
     */
    private fun tipStillAdvancingOrAllowRetry(file: File, path: String, force: Boolean): Boolean {
        if (!file.exists()) return true
        val preferZero = force || DownloadPathHeuristic.looksLikeDownloadManagerPath(path)
        val tip1 = SparseAwareFileLength.readableEnd(
            path,
            file.length(),
            fd = null,
            preferZeroTailScan = preferZero,
        )
        try {
            Thread.sleep(STABLE_POLL_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        }
        val tip2 = SparseAwareFileLength.readableEnd(
            path,
            file.length(),
            fd = null,
            preferZeroTailScan = preferZero,
        )
        // Advancing tip → definitely keep going; stuck tip → still allow until max retries.
        return true
    }

    private fun isDownloadManagerLike(uri: Uri): Boolean {
        val path = runCatching { appContext.getPath(uri) }.getOrNull()
            ?: GrowingFileDataSource.resolvePath(uri)
        if (DownloadPathHeuristic.looksLikeDownloadManagerPath(path)) return true
        if (DownloadPathHeuristic.looksLikeDownloadManagerPath(uri.toString())) return true
        if (path != null) {
            val name = File(path).name
            if (DownloadPathHeuristic.looksIncompleteDownload(path, name)) return true
        }
        if (ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) {
            val displayName = queryDisplayName(uri)
            if (DownloadPathHeuristic.looksIncompleteDownload(uri.toString(), displayName)) {
                return true
            }
        }
        return false
    }

    private fun isVarintOrEbmlParseError(error: IOException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            val msg = cause.message.orEmpty()
            val name = cause.javaClass.name
            if (msg.contains("No valid varint length mask", ignoreCase = true) ||
                msg.contains("VarintReader", ignoreCase = true) ||
                name.contains("VarintReader") ||
                name.contains("DefaultEbmlReader") ||
                name.contains("MatroskaExtractor")
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(
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

    private fun queryContentSize(uri: Uri): Long? {
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isRetriableWhileGrowing(error: IOException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            when (cause) {
                is UnrecognizedInputFormatException,
                is ParserException,
                is EOFException,
                -> return true
            }
            cause = cause.cause
        }
        // Generic IO / source errors while bytes are still arriving.
        return true
    }

    companion object {
        /** ~500–700ms between attempts; 14 × 600ms ≈ enough for header after a short pause. */
        private const val RETRY_DELAY_MS = 600L
        private const val STABLE_POLL_MS = 200L
        /** Below this size, headers may not be snifftable yet — allow brief retries. */
        private const val SNIFF_READY_BYTES = 64L * 1024L
        /**
         * Floor for ExoPlayer's minimum loadable retry count — covers download-path budget.
         */
        private const val MIN_GROWING_LOADABLE_RETRIES = 50
        /** Hard cap on ordinary growing-local retries (not a long demux wait). */
        const val MAX_GROWING_RETRIES = 14
        /**
         * Longer budget for 1DM/Download-path / zero-tail / varint errors (~50 × 600ms ≈ 30s)
         * so a stuck tip still fails, but a slowly advancing download keeps retrying.
         */
        const val MAX_DOWNLOAD_PATH_RETRIES = 50
    }
}
