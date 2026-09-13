package dev.anilbeesetti.nextplayer.core.common.extensions

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text sidecar subtitle extensions discovered next to a video file. */
val SUBTITLE_FILE_EXTENSIONS = listOf("srt", "ssa", "ass", "vtt", "ttml")

/**
 * Modest language / variant tags probed when directory listing is unavailable
 * (scoped storage on Android 13+ with only READ_MEDIA_VIDEO).
 */
val SUBTITLE_LANGUAGE_TAGS = listOf(
    "en", "eng", "en.forced", "eng.forced", "en.sdh", "eng.sdh",
    "es", "spa", "fr", "fre", "fra", "de", "ger", "deu",
    "it", "ita", "pt", "por", "pt.br", "ru", "rus",
    "ja", "jpn", "zh", "chi", "zho", "ko", "kor",
    "hi", "hin", "ar", "ara",
    "forced", "sdh", "cc",
)

/**
 * Finds subtitle sidecar files in the same directory as this media file.
 *
 * Matches exact basename (`video.vtt`) and language-tagged variants
 * (`video.en.vtt`, `video.en.srt`). Exact basename matches are listed first,
 * then tagged variants, sorted by filename. All matches are returned so the
 * player can expose them as selectable tracks.
 *
 * Prefers [File.listFiles] when available (finds arbitrary language tags).
 * When listing returns null/empty — common with scoped storage without all-files
 * access — probes known basename + extension (+ language tag) candidates.
 */
suspend fun File.getSubtitles(): List<File> = withContext(Dispatchers.IO) {
    findSubtitleSidecars(this@getSubtitles)
}

/**
 * Pure discovery used by [getSubtitles]; exposed for unit tests.
 *
 * @param listDirectory injectable directory listing; defaults to [File.listFiles].
 */
fun findSubtitleSidecars(
    mediaFile: File,
    listDirectory: (File) -> Array<File>? = { it.listFiles() },
): List<File> {
    val mediaName = mediaFile.nameWithoutExtension
    val parentDir = mediaFile.parentFile ?: return emptyList()
    val mediaNameLower = mediaName.lowercase()

    val listed = listDirectory(parentDir)
        ?.asSequence()
        ?.filter { it.isFile }
        ?.filter { it.extension.lowercase() in SUBTITLE_FILE_EXTENSIONS }
        ?.filter { candidate -> matchesSubtitleBasename(mediaNameLower, candidate.nameWithoutExtension) }
        ?.toList()
        .orEmpty()

    val candidates = if (listed.isNotEmpty()) {
        listed
    } else {
        probeSubtitleSidecars(parentDir, mediaName)
    }

    return candidates.sortedWith(
        compareBy<File> { candidate ->
            if (candidate.nameWithoutExtension.equals(mediaName, ignoreCase = true)) 0 else 1
        }.thenBy { it.name.lowercase() },
    )
}

/**
 * Probes candidate sidecar paths without directory listing.
 * Exact basename extensions first, then modest language-tagged variants.
 */
fun probeSubtitleSidecars(parentDir: File, mediaName: String): List<File> {
    val found = LinkedHashSet<File>()
    for (ext in SUBTITLE_FILE_EXTENSIONS) {
        val exact = File(parentDir, "$mediaName.$ext")
        if (exact.isFile) found.add(exact)
    }
    for (tag in SUBTITLE_LANGUAGE_TAGS) {
        for (ext in SUBTITLE_FILE_EXTENSIONS) {
            val tagged = File(parentDir, "$mediaName.$tag.$ext")
            if (tagged.isFile) found.add(tagged)
        }
    }
    return found.toList()
}

/**
 * True when [subtitleBaseName] is an exact or language-tagged match for [mediaNameLower].
 * Examples for media `movie`: `movie`, `movie.en`, `movie.en.forced` — not `movies` or `movieextra`.
 */
fun matchesSubtitleBasename(mediaNameLower: String, subtitleBaseName: String): Boolean {
    val base = subtitleBaseName.lowercase()
    return base == mediaNameLower || base.startsWith("$mediaNameLower.")
}



/**
 * Decodes a URI path segment / filename for UI display.
 * Turns `My%20Movie.en.vtt` into `My Movie.en.vtt`.
 * Pure JVM helper (no Android Uri) so unit tests do not touch MediaStore.
 */
fun decodeUriDisplayName(name: String): String {
    if (name.isEmpty()) return name
    val leaf = name.substringAfterLast('/').substringAfterLast(':')
    if ('%' !in leaf) return leaf
    return try {
        URLDecoder.decode(leaf.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        leaf
    }
}

/**
 * Extracts a language / locale tag from a subtitle sidecar filename.
 * Examples: `movie.en.vtt` → `en`, `movie.eng.srt` → `eng`,
 * `movie.en.forced.vtt` → `en`. Exact basename (`movie.srt`) → null.
 */
fun extractSubtitleLanguageFromFilename(filename: String): String? {
    val leaf = decodeUriDisplayName(filename)
    val base = leaf.substringBeforeLast('.').takeIf { it != leaf } ?: return null
    val parts = base.split('.').filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    val skip = setOf("forced", "sdh", "cc")
    for (part in parts.drop(1)) {
        val tag = part.lowercase()
        if (tag in skip) continue
        if (tag.length in 2..3 && tag.all { it.isLetter() }) return tag
        if (tag.matches(Regex("^[a-z]{2}(-[a-z]{2,8})?$", RegexOption.IGNORE_CASE))) {
            return tag.substringBefore('-').lowercase()
        }
    }
    return null
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
