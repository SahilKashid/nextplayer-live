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
import dev.anilbeesetti.nextplayer.core.media.network.datasource.IncompleteLocalMedia
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
 * Retries while the file looks incomplete ([IncompleteLocalMedia]): partial name,
 * readable tip behind declared length, tiny / unsnifftable header, or tip still
 * advancing. Small cap when the tip is stuck. Fail-fast when the file looks complete
 * (no zero tail, hole tip == declared, not a partial name).
 *
 * Directory names are never used.
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
        if (!isLocalUri(uri)) return false
        if (!isRetriableWhileGrowing(loadErrorInfo.exception)) return false
        return appearsStillGrowingOrIncomplete(uri, loadErrorInfo.errorCount)
    }

    private fun isLocalUri(uri: Uri): Boolean {
        val scheme = uri.scheme
        return scheme.isNullOrEmpty() ||
            ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true) ||
            ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)
    }

    private fun appearsStillGrowingOrIncomplete(uri: Uri, errorCount: Int): Boolean {
        val path = runCatching { appContext.getPath(uri) }.getOrNull()
            ?: GrowingFileDataSource.resolvePath(uri)
        if (path != null) {
            val file = File(path)
            if (IncompleteLocalMedia.looksPartialFileName(file.name) ||
                IncompleteLocalMedia.looksPartialFileName(path)
            ) {
                return errorCount < retryCap(tipAdvancing = tipAdvancing(path))
            }
            if (!file.exists()) return errorCount < MAX_GROWING_RETRIES
            val snapshot = IncompleteLocalMedia.inspect(path)
            if (snapshot.declaredLength in 0 until SNIFF_READY_BYTES) {
                return errorCount < MAX_GROWING_RETRIES
            }
            if (!snapshot.incomplete) {
                // Looks complete — fail fast unless the tip is actually advancing.
                return errorCount < MAX_GROWING_RETRIES && tipAdvancing(path)
            }
            return errorCount < retryCap(tipAdvancing = tipAdvancing(path))
        }

        if (ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) {
            val displayName = queryDisplayName(uri)
            if (GrowingContentDataSource.looksPartialName(displayName) ||
                GrowingContentDataSource.looksPartialName(uri.lastPathSegment)
            ) {
                return errorCount < MAX_GROWING_RETRIES
            }
            val size = queryContentSize(uri)
            if (size != null && size < SNIFF_READY_BYTES) {
                return errorCount < MAX_GROWING_RETRIES
            }
            return false
        }
        return false
    }

    private fun retryCap(tipAdvancing: Boolean): Int =
        if (tipAdvancing) MAX_GROWING_RETRIES else MAX_STUCK_RETRIES

    private fun tipAdvancing(path: String): Boolean {
        val first = IncompleteLocalMedia.inspect(path)
        try {
            Thread.sleep(STABLE_POLL_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        }
        val second = IncompleteLocalMedia.inspect(path)
        return second.readableEnd > first.readableEnd ||
            second.declaredLength > first.declaredLength
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
            // Matroska varint / EBML races while reading a zero tail.
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
        // Generic IO / source errors while bytes are still arriving.
        return true
    }

    companion object {
        /** ~500–700ms between attempts. */
        private const val RETRY_DELAY_MS = 600L
        private const val STABLE_POLL_MS = 200L
        /** Below this size, headers may not be snifftable yet — allow brief retries. */
        private const val SNIFF_READY_BYTES = 64L * 1024L
        /** Floor for ExoPlayer's minimum loadable retry count. */
        private const val MIN_GROWING_LOADABLE_RETRIES = 12
        /** Cap while the tip is advancing or the file is tiny / partial. */
        const val MAX_GROWING_RETRIES = 12
        /** Smaller cap when the tip is stuck (incomplete but not writing). */
        const val MAX_STUCK_RETRIES = 6
    }
}
