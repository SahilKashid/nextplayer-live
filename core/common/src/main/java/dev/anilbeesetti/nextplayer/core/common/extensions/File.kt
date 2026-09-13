package dev.anilbeesetti.nextplayer.core.common.extensions

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text sidecar subtitle extensions discovered next to a video file. */
val SUBTITLE_FILE_EXTENSIONS = listOf("srt", "ssa", "ass", "vtt", "ttml")

/**
 * Finds subtitle sidecar files in the same directory as this media file.
 *
 * Matches exact basename (`video.vtt`) and language-tagged variants
 * (`video.en.vtt`, `video.en.srt`). Exact basename matches are listed first,
 * then tagged variants, sorted by filename. All matches are returned so the
 * player can expose them as selectable tracks.
 */
suspend fun File.getSubtitles(): List<File> = withContext(Dispatchers.IO) {
    findSubtitleSidecars(this@getSubtitles)
}

/**
 * Pure discovery used by [getSubtitles]; exposed for unit tests.
 */
fun findSubtitleSidecars(mediaFile: File): List<File> {
    val mediaName = mediaFile.nameWithoutExtension
    val parentDir = mediaFile.parentFile ?: return emptyList()
    val mediaNameLower = mediaName.lowercase()
    val files = parentDir.listFiles() ?: return emptyList()

    return files.asSequence()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in SUBTITLE_FILE_EXTENSIONS }
        .filter { candidate -> matchesSubtitleBasename(mediaNameLower, candidate.nameWithoutExtension) }
        .sortedWith(
            compareBy<File> { candidate ->
                if (candidate.nameWithoutExtension.equals(mediaName, ignoreCase = true)) 0 else 1
            }.thenBy { it.name.lowercase() },
        )
        .toList()
}

/**
 * True when [subtitleBaseName] is an exact or language-tagged match for [mediaNameLower].
 * Examples for media `movie`: `movie`, `movie.en`, `movie.en.forced` — not `movies` or `movieextra`.
 */
fun matchesSubtitleBasename(mediaNameLower: String, subtitleBaseName: String): Boolean {
    val base = subtitleBaseName.lowercase()
    return base == mediaNameLower || base.startsWith("$mediaNameLower.")
}

suspend fun File.getLocalSubtitles(
    context: Context,
    excludeSubsList: List<Uri> = emptyList(),
): List<Uri> = withContext(Dispatchers.Default) {
    val excludeSubsPathSet = excludeSubsList.mapNotNull { context.getPath(it) }.toSet()

    getSubtitles().mapNotNull { file ->
        if (file.path !in excludeSubsPathSet) {
            file.toUri()
        } else {
            null
        }
    }
}

fun String.getThumbnail(): File? {
    val filePathWithoutExtension = this.substringBeforeLast(".")
    val imageExtensions = listOf("png", "jpg", "jpeg")
    for (imageExtension in imageExtensions) {
        val file = File("$filePathWithoutExtension.$imageExtension")
        if (file.exists()) return file
    }
    return null
}

fun File.isSubtitle(): Boolean {
    return extension.lowercase() in SUBTITLE_FILE_EXTENSIONS
}

fun File.deleteFiles() {
    try {
        listFiles()?.onEach {
            it.delete()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

val File.prettyName: String
    get() = this.name.takeIf { this.path != Environment.getExternalStorageDirectory()?.path } ?: "Internal Storage"
