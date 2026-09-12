package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
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
 * A file that **was** growing is treated as settled-complete when it is not a
 * partial name and the readable tip has caught the declared length (or the last
 * ~256KiB already has real bytes and any SEEK_HOLE is far from EOF — a false
 * hole on a finished file). Recent mtime alone does not keep it growing: a short
 * poll must still see the tip / declared length advance.
 *
 * Never keys off directory names (`1DM`, `Download`, `ADM`, …). A zero-preallocated
 * incomplete MKV under `/Movies/` is treated the same as one under any other folder.
 * A finished MKV whose tail contains real cues / non-zero data is **not** incomplete,
 * so playback uses a finite-length source and Matroska cue-seek stays enabled.
 *
 * Unresolvable `content://` URIs (Open-with / share sheet) are inspected the same way via
 * [ContentResolver.openAssetFileDescriptor] + [SparseAwareFileLength] on the AFD/PFD —
 * finished content URIs must leave the growing path just like finished file paths.
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

        /**
         * Finished: not a partial name, declared size is known, and the readable
         * tip has caught up (SEEK_HOLE / zero-tail no longer behind).
         */
        val settledComplete: Boolean
            get() = !partialName && declaredLength > 0L && !tipBehindDeclared
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

    /**
     * True when local playback should use the growing DataSource / disable
     * Matroska cue-seek / wrap with the incomplete-MKV extractor.
     *
     * Missing files and partial names are growing. Content-incomplete (hole /
     * zero tail) is growing. A settled-complete file with stable mtime is not.
     * A settled-complete file whose mtime is recent is growing only if declared
     * or readable length still advances across [growthPollMs] (append-style).
     */
    fun shouldPlayAsGrowing(
        path: String,
        fileName: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        growthPollMs: Long = DEFAULT_GROWTH_POLL_MS,
    ): Boolean {
        if (looksPartialFileName(fileName)) return true
        val file = File(path)
        if (looksPartialFileName(file.name) || looksPartialFileName(path)) return true
        if (!file.exists()) return true
        val snapshot = inspect(path, fileName)
        if (snapshot.incomplete) return true
        val age = nowMs - file.lastModified()
        if (age < 0L) return true
        if (age < RECENT_MTIME_FOR_POLL_MS) {
            return isGrowingAcrossPoll(path, growthPollMs)
        }
        return false
    }


    /**
     * Content-URI variant of [shouldPlayAsGrowing]: inspects bytes through an AFD/PFD when
     * no filesystem path is available (typical for ACTION_VIEW / Open-with / share sheet).
     *
     * Returns true when the URI cannot be opened or declared length is unknown — safer to
     * stay on the growing path (wait/retry) than to assume a finite EOF.
     */
    fun shouldPlayAsGrowing(
        context: Context,
        uri: Uri,
        nowMs: Long = System.currentTimeMillis(),
        growthPollMs: Long = DEFAULT_GROWTH_POLL_MS,
    ): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) {
            val path = GrowingFileDataSource.resolvePath(uri)
            return if (path != null) {
                shouldPlayAsGrowing(path, nowMs = nowMs, growthPollMs = growthPollMs)
            } else {
                true
            }
        }
        val displayName = queryDisplayName(context, uri)
        if (looksPartialFileName(displayName) || looksPartialFileName(uri.lastPathSegment)) {
            return true
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val declared = declaredLengthOf(afd)
                if (declared < 0L) return true
                val fd = try {
                    afd.parcelFileDescriptor?.fileDescriptor ?: afd.fileDescriptor
                } catch (_: Exception) {
                    null
                }
                shouldPlayAsGrowing(
                    declaredLength = declared,
                    fd = fd,
                    fileName = displayName,
                    lastModifiedMs = queryLastModifiedMs(context, uri),
                    nowMs = nowMs,
                    growthCheck = { isGrowingAcrossPoll(context, uri, growthPollMs) },
                )
            } ?: true
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Pure decision used by both path and content-URI entry points once a declared length
     * and optional FD / mtime are known. Unit-testable without a ContentResolver.
     */
    fun shouldPlayAsGrowing(
        declaredLength: Long,
        fd: FileDescriptor?,
        fileName: String?,
        lastModifiedMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
        growthCheck: (() -> Boolean)? = null,
    ): Boolean {
        if (looksPartialFileName(fileName)) return true
        if (declaredLength < 0L) return true
        if (declaredLength == 0L) return true
        val snapshot = inspect(declaredLength, fd, fileName)
        if (snapshot.incomplete) return true
        val lastMod = lastModifiedMs ?: return false
        val age = nowMs - lastMod
        if (age < 0L) return true
        if (age < RECENT_MTIME_FOR_POLL_MS) {
            return growthCheck?.invoke() ?: false
        }
        return false
    }

    /** Inspect a `content://` URI via AFD/PFD. Missing / unreadable → declaredLength = -1. */
    fun inspect(context: Context, uri: Uri): Snapshot {
        val displayName = queryDisplayName(context, uri)
        val partial = looksPartialFileName(displayName) ||
            looksPartialFileName(uri.lastPathSegment)
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val declared = declaredLengthOf(afd)
                if (declared < 0L) {
                    Snapshot(declaredLength = -1L, readableEnd = -1L, partialName = partial)
                } else {
                    val fd = try {
                        afd.parcelFileDescriptor?.fileDescriptor ?: afd.fileDescriptor
                    } catch (_: Exception) {
                        null
                    }
                    val snap = inspect(declared, fd, displayName)
                    if (partial && !snap.partialName) {
                        snap.copy(partialName = true)
                    } else {
                        snap
                    }
                }
            } ?: Snapshot(declaredLength = -1L, readableEnd = -1L, partialName = partial)
        } catch (_: Exception) {
            Snapshot(declaredLength = -1L, readableEnd = -1L, partialName = partial)
        }
    }

    fun isGrowingAcrossPoll(
        context: Context,
        uri: Uri,
        pollMs: Long = DEFAULT_GROWTH_POLL_MS,
    ): Boolean {
        val first = inspect(context, uri)
        try {
            Thread.sleep(pollMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        }
        val second = inspect(context, uri)
        return second.declaredLength > first.declaredLength ||
            second.readableEnd > first.readableEnd
    }

    internal fun declaredLengthOf(afd: AssetFileDescriptor): Long {
        val reported = afd.length
        if (reported != AssetFileDescriptor.UNKNOWN_LENGTH && reported >= 0L) {
            return reported
        }
        // Prefer PFD statSize — do not wrap afd.fileDescriptor in a stream we close,
        // or the AFD's FD is invalidated for the subsequent tip probe.
        return try {
            val pfd = afd.parcelFileDescriptor
            if (pfd != null) {
                val stat = pfd.statSize
                if (stat >= 0L) {
                    return (stat - afd.startOffset).coerceAtLeast(0L)
                }
            }
            -1L
        } catch (_: Exception) {
            -1L
        }
    }

    internal fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
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

    internal fun queryLastModifiedMs(context: Context, uri: Uri): Long? {
        val candidates = listOf(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            "last_modified",
            "date_modified",
        )
        for (column in candidates) {
            try {
                context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(column)
                        if (idx >= 0 && !cursor.isNull(idx)) {
                            val raw = cursor.getLong(idx)
                            if (raw > 0L) {
                                // MediaStore DATE_MODIFIED is typically seconds.
                                return if (column == MediaStore.MediaColumns.DATE_MODIFIED ||
                                    column == "date_modified"
                                ) {
                                    if (raw < 10_000_000_000L) raw * 1000L else raw
                                } else {
                                    raw
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // try next column
            }
        }
        return null
    }

        const val DEFAULT_GROWTH_POLL_MS = 250L

    /** Only run the optional append-style growth poll when mtime is this fresh. */
    const val RECENT_MTIME_FOR_POLL_MS = 30_000L
}
