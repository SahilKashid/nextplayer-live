package dev.anilbeesetti.nextplayer.feature.player.service

import android.content.ContentResolver
import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.EOFException
import java.io.IOException

/**
 * Retries progressive load/source errors for local growing files (`file://` / `content://`)
 * so incomplete containers (especially MP4 without an early `moov`) can become playable as the
 * download appends headers.
 *
 * [DefaultLoadErrorHandlingPolicy] treats [ParserException] /
 * [UnrecognizedInputFormatException] as non-retriable, which surfaces as an immediate
 * "Source error" / "Can't play video" while the file is still growing.
 */
@UnstableApi
class GrowingFileLoadErrorHandlingPolicy(
    private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
) : LoadErrorHandlingPolicy by delegate {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (shouldRetryGrowingLocal(loadErrorInfo)) {
            return RETRY_DELAY_MS
        }
        return delegate.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        // Allow enough attempts for moov/headers to land (~45s at 1s delay).
        return maxOf(delegate.getMinimumLoadableRetryCount(dataType), MAX_GROWING_RETRIES)
    }

    private fun shouldRetryGrowingLocal(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Boolean {
        if (loadErrorInfo.errorCount >= MAX_GROWING_RETRIES) return false
        val uri = loadErrorInfo.loadEventInfo.uri
            ?: loadErrorInfo.loadEventInfo.dataSpec.uri
            ?: (loadErrorInfo.exception as? UnrecognizedInputFormatException)?.uri
            ?: return false
        if (!isLocalGrowingUri(uri)) return false
        return isRetriableWhileGrowing(loadErrorInfo.exception)
    }

    private fun isLocalGrowingUri(uri: Uri): Boolean {
        val scheme = uri.scheme
        return scheme.isNullOrEmpty() ||
            ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true) ||
            ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)
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
        private const val RETRY_DELAY_MS = 1_000L
        private const val MAX_GROWING_RETRIES = 45
    }
}
