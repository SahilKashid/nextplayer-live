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
import dev.anilbeesetti.nextplayer.core.media.network.datasource.GrowingContentDataSource
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
 * Caps are hard (~8 attempts at ~500–750ms): a few seconds total, not tens of seconds.
 * If the file is already large enough to sniff, length-stable across a short poll, and
 * does not look like a partial download name, errors surface immediately.
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
        if (loadErrorInfo.errorCount >= MAX_GROWING_RETRIES) return false
        val uri = loadErrorInfo.loadEventInfo.uri
            ?: loadErrorInfo.loadEventInfo.dataSpec.uri
            ?: (loadErrorInfo.exception as? UnrecognizedInputFormatException)?.uri
            ?: return false
        if (!isLocalUri(uri)) return false
        if (!isRetriableWhileGrowing(loadErrorInfo.exception)) return false
        return appearsStillGrowingOrIncomplete(uri)
    }

    private fun isLocalUri(uri: Uri): Boolean {
        val scheme = uri.scheme
        return scheme.isNullOrEmpty() ||
            ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true) ||
            ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)
    }

    /**
     * Retry only while the file is still tiny, actively growing (length increases on a
     * short poll), or looks like a partial download. Large + stable + non-partial → no.
     */
    private fun appearsStillGrowingOrIncomplete(uri: Uri): Boolean {
        val path = runCatching { appContext.getPath(uri) }.getOrNull()
            ?: GrowingFileDataSource.resolvePath(uri)
        if (path != null) {
            val file = File(path)
            if (GrowingFileDataSource.looksPartialFileName(file.name) ||
                GrowingFileDataSource.looksPartialFileName(path)
            ) {
                return true
            }
            if (!file.exists()) return true
            val length1 = file.length()
            if (length1 < SNIFF_READY_BYTES) {
                return true
            }
            try {
                Thread.sleep(STABLE_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return true
            }
            val length2 = file.length()
            // Actively growing — brief wait may help a still-arriving header.
            return length2 > length1
        }

        if (ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) {
            val displayName = queryDisplayName(uri)
            if (GrowingContentDataSource.looksPartialName(displayName) ||
                GrowingContentDataSource.looksPartialName(uri.lastPathSegment)
            ) {
                return true
            }
            val size = queryContentSize(uri)
            if (size != null && size < SNIFF_READY_BYTES) {
                return true
            }
            // Unresolved content:// that is already large (or size unknown) and not partial:
            // do not keep retrying — let the error surface quickly.
            return false
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
        /** ~500–750ms between attempts; 8 × 600ms ≈ a few seconds total. */
        private const val RETRY_DELAY_MS = 600L
        private const val STABLE_POLL_MS = 200L
        /** Below this size, headers may not be snifftable yet — allow brief retries. */
        private const val SNIFF_READY_BYTES = 64L * 1024L
        /**
         * Floor for ExoPlayer's minimum loadable retry count — matches [MAX_GROWING_RETRIES].
         */
        private const val MIN_GROWING_LOADABLE_RETRIES = 8
        /** Hard cap on growing-local retries (not the old ~45 / 300 long demux wait). */
        const val MAX_GROWING_RETRIES = 8
    }
}
