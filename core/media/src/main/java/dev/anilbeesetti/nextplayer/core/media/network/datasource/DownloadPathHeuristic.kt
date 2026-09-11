package dev.anilbeesetti.nextplayer.core.media.network.datasource

/**
 * Detects paths / URIs that look like an in-progress download-manager destination.
 *
 * 1DM and similar tools often preallocate the **full** file with real zero bytes (not sparse
 * holes), leave mtime stale while writing, and keep a final `.mkv` name — so length/mtime/
 * SEEK_HOLE heuristics alone think the file is finished. Matching the download folder / manager
 * name forces growing-file behavior (disable cue-seek, zero-tail tip, keep retrying).
 */
object DownloadPathHeuristic {

    /** Partial-download filename suffixes (same set as historical Growing* helpers). */
    val PARTIAL_SUFFIXES: List<String> = listOf(
        ".part",
        ".crdownload",
        ".!ut",
        ".tmp",
        ".download",
        ".aria2",
        ".bc!",
    )

    /**
     * Short download-manager / folder tokens matched as path **segments** (not raw substrings)
     * so e.g. `adm` does not match `admin` inside an unrelated filename.
     */
    private val PATH_SEGMENT_MARKERS: List<String> = listOf(
        "1dm",
        "adm",
        "idm",
        "aria2",
        "download",
        "downloads",
    )

    /** Longer markers matched anywhere in the normalized path / URI. */
    private val SUBSTRING_MARKERS: List<String> = listOf(
        "/download/",
        "/downloads/",
        ".crdownload",
        ".part",
        ".!ut",
        ".aria2",
        "1dm",
    )

    /**
     * True when [pathOrUri] looks like a download-manager destination (folder or partial name).
     *
     * Examples that match:
     * - `/storage/emulated/0/Download/1DM/Videos/show.mkv`
     * - `.../Downloads/movie.mkv.crdownload`
     * - path containing `/ADM/` or `/IDM/` as a folder
     */
    fun looksLikeDownloadManagerPath(pathOrUri: String?): Boolean {
        if (pathOrUri.isNullOrEmpty()) return false
        val normalized = pathOrUri.replace('\\', '/').lowercase()
        if (PARTIAL_SUFFIXES.any { normalized.endsWith(it) }) return true
        if (SUBSTRING_MARKERS.any { normalized.contains(it) }) return true
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.any { segment -> PATH_SEGMENT_MARKERS.any { it == segment } }) {
            return true
        }
        // Parent folder named 1DM / ADM / IDM; also accept segments starting with 1dm.
        if (segments.any { it.startsWith("1dm") || it == "adm" || it == "idm" }) {
            return true
        }
        return false
    }

    /** True when the basename looks like a partial download artifact. */
    fun looksPartialFileName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val lower = name.lowercase()
        return PARTIAL_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * Combined: download-manager path / folder **or** partial filename.
     * Prefer this from extractors / error policy / diagnostics.
     */
    fun looksIncompleteDownload(pathOrUri: String?, fileName: String? = null): Boolean {
        if (looksLikeDownloadManagerPath(pathOrUri)) return true
        if (looksPartialFileName(fileName)) return true
        if (fileName != null && looksLikeDownloadManagerPath(fileName)) return true
        if (fileName == null && pathOrUri != null) {
            val base = pathOrUri.replace('\\', '/').substringAfterLast('/')
            if (looksPartialFileName(base)) return true
        }
        return false
    }
}
