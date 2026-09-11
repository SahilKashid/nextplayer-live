package dev.anilbeesetti.nextplayer.feature.player.utils

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.media.network.datasource.GrowingFileDataSource
import dev.anilbeesetti.nextplayer.core.media.network.datasource.SparseAwareFileLength
import dev.anilbeesetti.nextplayer.feature.player.service.GrowingAwareExtractorsFactory
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds a plain-text playback-failure report that the user can copy from the in-app error
 * dialog (no ADB required).
 */
@OptIn(UnstableApi::class)
object PlaybackFailureLog {

    const val HEADER = "Next Player Live playback failure diagnostic"
    const val MAX_CHARS = 12_000

    data class AppInfo(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long?,
    )

    fun appInfo(context: Context): AppInfo {
        val packageName = context.packageName
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            AppInfo(packageName, info.versionName, versionCode)
        } catch (_: Exception) {
            AppInfo(packageName, versionName = null, versionCode = null)
        }
    }

    fun nowIso(timeMs: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timeMs))
    }

    fun build(
        error: PlaybackException,
        mediaUri: Uri?,
        mediaId: String?,
        appInfo: AppInfo? = null,
        context: Context? = null,
        timestamp: String = nowIso(),
    ): String {
        val resolvedAppInfo = appInfo ?: context?.let { appInfo(it) }
        val body = buildString {
            appendLine(HEADER)
            appendLine("timestamp: $timestamp")
            appendLine("package: ${resolvedAppInfo?.packageName ?: "<unknown>"}")
            appendLine(
                "version: ${resolvedAppInfo?.versionName ?: "<unknown>"} " +
                    "(${resolvedAppInfo?.versionCode ?: "<unknown>"})",
            )
            appendLine("mediaId: ${mediaId ?: "<none>"}")
            appendLine("mediaUri: ${mediaUri ?: "<none>"}")
            appendLine("scheme: ${mediaUri?.scheme ?: "<none>"}")
            appendLine()
            appendLine("PlaybackException.errorCode: ${error.errorCode}")
            appendLine("PlaybackException.errorCodeName: ${error.errorCodeName}")
            appendLine("PlaybackException.message: ${error.message}")
            appendLine("PlaybackException.timestampMs: ${error.timestampMs}")
            if (error is ExoPlaybackException) {
                appendLine("ExoPlaybackException.type: ${exoTypeName(error.type)} (${error.type})")
                if (error.rendererName != null) {
                    appendLine("ExoPlaybackException.rendererName: ${error.rendererName}")
                }
                if (error.type == ExoPlaybackException.TYPE_RENDERER) {
                    appendLine("ExoPlaybackException.rendererIndex: ${error.rendererIndex}")
                }
            }
            appendLine()
            appendLine("Cause chain:")
            append(causeChain(error))
            appendLine()
            appendLine("Stack traces:")
            append(stackTraces(error))
            if (context != null && mediaUri != null) {
                appendLine()
                appendLine("Local file diagnostics:")
                append(localFileDiagnostics(context, mediaUri))
            }
        }
        return truncate(body)
    }

    private fun exoTypeName(type: Int): String = when (type) {
        ExoPlaybackException.TYPE_SOURCE -> "SOURCE"
        ExoPlaybackException.TYPE_RENDERER -> "RENDERER"
        ExoPlaybackException.TYPE_UNEXPECTED -> "UNEXPECTED"
        ExoPlaybackException.TYPE_REMOTE -> "REMOTE"
        else -> "UNKNOWN"
    }

    private fun causeChain(throwable: Throwable): String = buildString {
        val seen = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 12 && seen.put(current, true) == null) {
            appendLine("  [$depth] ${current.javaClass.name}: ${current.message}")
            current = current.cause
            depth++
        }
    }

    private fun stackTraces(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun localFileDiagnostics(context: Context, uri: Uri): String = buildString {
        val scheme = uri.scheme
        val isLocal = scheme.isNullOrEmpty() ||
            ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true) ||
            ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)
        if (!isLocal) {
            appendLine("  skipped (not a local file/content URI)")
            return@buildString
        }
        val path = runCatching { context.getPath(uri) }.getOrNull()
            ?: GrowingFileDataSource.resolvePath(uri)
        if (path == null) {
            val growing = runCatching {
                GrowingAwareExtractorsFactory.isLikelyGrowing(context, uri)
            }.getOrElse { "error: ${it.message}" }
            appendLine("  path: <unresolved>")
            appendLine("  isLikelyGrowing: $growing")
            return@buildString
        }
        val file = File(path)
        val exists = runCatching { file.exists() }.getOrDefault(false)
        val declared = runCatching { file.length() }.getOrDefault(-1L)
        val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
        val readableEnd = runCatching {
            SparseAwareFileLength.readableEnd(path, declared.coerceAtLeast(0L), fd = null)
        }.getOrElse { -1L }
        val partial = GrowingFileDataSource.looksPartialFileName(file.name) ||
            GrowingFileDataSource.looksPartialFileName(path)
        val growing = runCatching {
            GrowingAwareExtractorsFactory.isLikelyGrowing(context, uri)
        }.getOrElse { "error: ${it.message}" }
        appendLine("  path: $path")
        appendLine("  exists: $exists")
        appendLine("  declaredLength: $declared")
        appendLine("  readableEnd: $readableEnd")
        appendLine(
            "  lastModified: $lastModified" +
                if (lastModified > 0L) " (${nowIso(lastModified)})" else "",
        )
        appendLine("  partialNameHeuristic: $partial")
        appendLine("  isLikelyGrowing: $growing")
    }

    internal fun truncate(text: String, maxChars: Int = MAX_CHARS): String {
        if (text.length <= maxChars) return text
        val marker = "\n...[truncated]"
        return text.take(maxChars - marker.length) + marker
    }
}

/**
 * Last playback-failure dump, kept in memory for the error dialog and persisted under
 * `filesDir/playback-failure-last.txt`.
 */
object PlaybackFailureLogStore {

    const val FILE_NAME = "playback-failure-last.txt"
    const val LOG_TAG = "PlaybackFailure"
    private const val LOGCAT_CHUNK = 3500

    private val lastReport = AtomicReference<String?>(null)

    fun latest(): String? = lastReport.get()

    fun save(context: Context, report: String) {
        lastReport.set(report)
        logToLogcat(report)
        runCatching {
            File(context.applicationContext.filesDir, FILE_NAME).writeText(report)
        }
    }

    internal fun clearForTest() {
        lastReport.set(null)
    }

    private fun logToLogcat(report: String) {
        if (report.length <= LOGCAT_CHUNK) {
            Log.e(LOG_TAG, report)
            return
        }
        var index = 0
        var part = 1
        val total = (report.length + LOGCAT_CHUNK - 1) / LOGCAT_CHUNK
        while (index < report.length) {
            val end = minOf(index + LOGCAT_CHUNK, report.length)
            Log.e(LOG_TAG, "[$part/$total] ${report.substring(index, end)}")
            index = end
            part++
        }
    }
}
