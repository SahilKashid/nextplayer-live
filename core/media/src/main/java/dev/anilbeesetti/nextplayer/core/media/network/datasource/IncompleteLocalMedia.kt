package dev.anilbeesetti.nextplayer.core.media.network.datasource

import java.io.File
import java.io.FileDescriptor

/**
 * Universal, **content-based** incomplete-local-media detection.
 *
 * A local file is incomplete when any of:
 * 1. Partial filename suffix (`.part`, `.crdownload`, `.!ut`, …)
 * 2. `SEEK_HOLE` readable end is behind [File.length]
 * 3. The last ~256KiB is all zeros **and** [SparseAwareFileLength.zeroPaddedReadableEnd]
 *    is behind the declared length
 * 4. Optional: declared / readable length grew across a short poll
 *
 * Never keys off directory names (`1DM`, `Download`, `ADM`, …). A zero-preallocated
 * incomplete MKV under `/Movies/` is treated the same as one under any other folder.
 * A finished MKV whose tail contains real cues / non-zero data is **not** incomplete,
 * so Matroska cue-seek stays enabled.
 */
object IncompleteLocalMedia {

    /** Partial-download filename suffixes (universal name signals, not folder names). */
    val PARTIAL_SUFFIXES: List<String> = listOf(
        ".part",
        ".crdownload",
        ".!ut",
        ".tmp",
        ".download",
        ".aria2",
        ".bc!",
    )

    data class Snapshot(
        val declaredLength: Long,
        val readableEnd: Long,
        val partialName: Boolean,
    ) {
        /** True when the readable tip is behind the declared size (sparse hole or zero tail). */
        val tipBehindDeclared: Boolean
            get() = SparseAwareFileLength.isSparsePartial(declaredLength, readableEnd)

        /** Content-based incomplete: partial name **or** tip behind declared length. */
        val incomplete: Boolean
            get() = partialName || tipBehindDeclared
    }

    /** True when [name] looks like an in-progress download artifact. */
    fun looksPartialFileName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val lower = name.lowercase()
        return PARTIAL_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * Inspect [path] on disk. Missing files yield [Snapshot.declaredLength] = -1
     * (callers decide whether that counts as growing).
     */
    fun inspect(path: String, fileName: String? = null): Snapshot {
        val file = File(path)
        val name = fileName ?: file.name
        val partial = looksPartialFileName(name) || looksPartialFileName(file.name)
        if (!file.exists()) {
            return Snapshot(declaredLength = -1L, readableEnd = -1L, partialName = partial)
        }
        val declared = file.length()
        val readable = SparseAwareFileLength.readableEnd(path, declared, fd = null)
        return Snapshot(declaredLength = declared, readableEnd = readable, partialName = partial)
    }

    /** Inspect an already-open [fd] (content AFD / RAF). */
    fun inspect(
        declaredLength: Long,
        fd: FileDescriptor?,
        fileName: String?,
    ): Snapshot {
        val readable = SparseAwareFileLength.readableEnd(declaredLength, fd)
        return Snapshot(
            declaredLength = declaredLength,
            readableEnd = readable,
            partialName = looksPartialFileName(fileName),
        )
    }

    /**
     * True when [path] is a partial name **or** the on-disk tip is behind declared length.
     * Missing files return false here (only the name is considered); callers that want
     * “file may still appear” should check [File.exists] themselves.
     */
    fun isIncomplete(path: String?, fileName: String? = null): Boolean {
        if (looksPartialFileName(fileName)) return true
        if (path.isNullOrEmpty()) return false
        val base = path.replace('\\', '/').substringAfterLast('/')
        if (looksPartialFileName(base)) return true
        val file = File(path)
        if (looksPartialFileName(file.name)) return true
        if (!file.exists()) return false
        return inspect(path, fileName).incomplete
    }

    fun isIncomplete(file: File): Boolean = inspect(file.absolutePath, file.name).incomplete

    /**
     * Optional growth check: declared length or readable tip increased across [pollMs].
     */
    fun isGrowingAcrossPoll(path: String, pollMs: Long = DEFAULT_GROWTH_POLL_MS): Boolean {
        val first = inspect(path)
        try {
            Thread.sleep(pollMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        }
        val second = inspect(path)
        return second.declaredLength > first.declaredLength ||
            second.readableEnd > first.readableEnd
    }

    const val DEFAULT_GROWTH_POLL_MS = 250L
}
