package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.common.extensions.subtitleCacheDir
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * Session LRU + disk cache for live-subtitle [TimedCue] timelines.
 *
 * Disk files live under [Context.subtitleCacheDir] as `live_cues_*.bin` (compact binary).
 * Legacy `live_cues_*.json` files are still read once and rewritten as binary.
 *
 * **Only complete final timelines** should be [put] — progressive demux partials stay in UI
 * state only ([SubtitleCueLoader] writes after extract/parse finishes).
 *
 * ## Key policy
 * Prefer stable `mediaId` + track identity. For `file`/`content` URIs include **size when
 * known** (≥ 0). **Do not** include `last_modified` — content providers often omit or flake
 * that column, which would churn the key and miss cache on reopen. When size is unavailable,
 * the key omits it consistently (empty field). URI strings are normalized (scheme lowercased,
 * stable encoded path/query) so the same file does not miss.
 */
@UnstableApi
object LiveSubtitleCueCache {

    private const val TAG = "LiveSubtitleCueCache"
    private const val VERSION = 2
    private const val MEMORY_CAPACITY = 32
    private const val FILE_PREFIX = "live_cues_"
    private const val MAGIC = 0x4C534342 // 'LSCB'

    private val lock = ReentrantLock()
    private val memory = object : LinkedHashMap<String, List<TimedCue>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TimedCue>>?): Boolean =
            size > MEMORY_CAPACITY
    }

    /** Debug counters (optional; not shown in UI). */
    private val memoryHits = AtomicInteger(0)
    private val diskHits = AtomicInteger(0)
    private val misses = AtomicInteger(0)
    private val puts = AtomicInteger(0)

    fun buildKey(
        mediaId: String?,
        mediaUri: Uri?,
        externalUri: Uri?,
        format: Format?,
        textTrackIndex: Int,
        context: Context,
    ): String {
        val uriForMeta = externalUri ?: mediaUri
        val size = uriFileSize(context, uriForMeta)
        return listOf(
            mediaId.orEmpty(),
            normalizeUriString(mediaUri),
            normalizeUriString(externalUri),
            format?.id.orEmpty(),
            format?.language.orEmpty(),
            format?.label.orEmpty(),
            format?.sampleMimeType.orEmpty(),
            format?.codecs.orEmpty(),
            textTrackIndex.toString(),
            // Size when known; empty when unavailable — never mtime (flaky / missing).
            size?.toString().orEmpty(),
        ).joinToString("|")
    }

    fun get(context: Context, key: String): List<TimedCue>? {
        lock.withLock {
            memory[key]?.takeIf { it.isNotEmpty() }?.let {
                memoryHits.incrementAndGet()
                logStats("memory-hit")
                return it
            }
        }
        val disk = readDisk(context, key) ?: run {
            misses.incrementAndGet()
            logStats("miss")
            return null
        }
        // Legacy empty files (from failed loads) must not stick as permanent misses.
        if (disk.isEmpty()) {
            runCatching { cacheFileBin(context, key).delete() }
            runCatching { cacheFileJson(context, key).delete() }
            misses.incrementAndGet()
            logStats("miss-empty-disk")
            return null
        }
        lock.withLock {
            memory[key] = disk
        }
        diskHits.incrementAndGet()
        logStats("disk-hit")
        return disk
    }

    /**
     * Persist a **complete** non-empty timeline. Callers must not put mid-progressive partials.
     */
    fun put(context: Context, key: String, cues: List<TimedCue>) {
        // Never persist empty timelines — a one-shot demux/parse failure would otherwise
        // lock the panel on empty/unsupported until cache invalidation.
        if (cues.isEmpty()) return
        lock.withLock {
            memory[key] = cues
        }
        writeDisk(context, key, cues)
        puts.incrementAndGet()
        logStats("put")
    }

    fun getMemoryOnly(key: String): List<TimedCue>? =
        lock.withLock {
            memory[key]?.takeIf { it.isNotEmpty() }?.also {
                memoryHits.incrementAndGet()
            }
        }

    /** Test helper: drop the in-memory LRU so the next [get] must hit disk. */
    internal fun clearMemory() {
        lock.withLock { memory.clear() }
    }

    /** Test / debug helper. */
    internal fun statsSnapshot(): Map<String, Int> = mapOf(
        "memoryHits" to memoryHits.get(),
        "diskHits" to diskHits.get(),
        "misses" to misses.get(),
        "puts" to puts.get(),
    )

    internal fun resetStats() {
        memoryHits.set(0)
        diskHits.set(0)
        misses.set(0)
        puts.set(0)
    }

    /** Normalize URI for stable cache keys (same file → same string). */
    internal fun normalizeUriString(uri: Uri?): String {
        if (uri == null) return ""
        return runCatching {
            Uri.Builder()
                .scheme(uri.scheme?.lowercase())
                .encodedAuthority(uri.encodedAuthority)
                .encodedPath(uri.encodedPath)
                .encodedQuery(uri.encodedQuery)
                // Drop fragments — they are not part of the media identity.
                .build()
                .toString()
        }.getOrElse { uri.toString() }
    }

    private fun logStats(event: String) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        Log.d(
            TAG,
            "$event memHits=${memoryHits.get()} diskHits=${diskHits.get()} " +
                "misses=${misses.get()} puts=${puts.get()}",
        )
    }

    private fun readDisk(context: Context, key: String): List<TimedCue>? {
        val bin = cacheFileBin(context, key)
        if (bin.exists() && bin.isFile) {
            readBinary(bin)?.let { return it }
            // Corrupt / wrong magic-version: delete so the next extract can rewrite.
            runCatching { bin.delete() }
        }
        val json = cacheFileJson(context, key)
        if (json.exists() && json.isFile) {
            val legacy = readJson(json)
            if (legacy == null) {
                runCatching { json.delete() }
                return null
            }
            // Migrate to binary for faster subsequent loads.
            writeBinary(bin, legacy)
            runCatching { json.delete() }
            return legacy
        }
        return null
    }

    private fun writeDisk(context: Context, key: String, cues: List<TimedCue>) {
        runCatching {
            val bin = cacheFileBin(context, key)
            bin.parentFile?.mkdirs()
            writeBinary(bin, cues)
            // Remove legacy JSON if present.
            runCatching { cacheFileJson(context, key).delete() }
        }
    }

    private fun writeBinary(file: File, cues: List<TimedCue>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            val bos = BufferedOutputStream(fos)
            val out = DataOutputStream(bos)
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(cues.size)
            for (cue in cues) {
                out.writeLong(cue.startMs)
                out.writeLong(cue.endMs)
                val bytes = cue.text.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.flush()
            bos.flush()
            // Ensure durable write before rename (reopen reliability).
            runCatching { fos.fd.sync() }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun readBinary(file: File): List<TimedCue>? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC) return null
            if (input.readInt() != VERSION) return null
            val count = input.readInt()
            if (count < 0 || count > 500_000) return null
            buildList<TimedCue>(count) {
                repeat(count) {
                    val start = input.readLong()
                    val end = input.readLong()
                    val len = input.readInt()
                    if (len < 0 || len > 1_000_000) return null
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    add(TimedCue(startMs = start, endMs = end, text = String(bytes, Charsets.UTF_8)))
                }
            }
        }
    }.getOrNull()

    private fun readJson(file: File): List<TimedCue>? = runCatching {
        val root = JSONObject(file.readText())
        val version = root.optInt("v")
        if (version != 1 && version != VERSION) return null
        val arr = root.getJSONArray("cues")
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    TimedCue(
                        startMs = o.getLong("s"),
                        endMs = o.getLong("e"),
                        text = o.getString("t"),
                    ),
                )
            }
        }
    }.getOrNull()

    private fun cacheFileBin(context: Context, key: String): File =
        File(context.subtitleCacheDir, "$FILE_PREFIX${digest(key)}.bin")

    private fun cacheFileJson(context: Context, key: String): File =
        File(context.subtitleCacheDir, "$FILE_PREFIX${digest(key)}.json")

    private fun digest(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
            .take(40)

    /**
     * Stable file size for cache keying. Returns null when size is unknown so the key
     * field stays empty consistently (no mtime — see class KDoc).
     */
    internal fun uriFileSize(context: Context, uri: Uri?): Long? {
        if (uri == null) return null
        return runCatching {
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val path = uri.path ?: return null
                    val f = File(path)
                    if (!f.exists()) return null
                    f.length().takeIf { it >= 0L }
                }
                "content" -> {
                    context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return null
                        val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIdx < 0 || cursor.isNull(sizeIdx)) return null
                        cursor.getLong(sizeIdx).takeIf { it >= 0L }
                    }
                }
                else -> null
            }
        }.getOrNull()
    }

    /** Exposed for tests that need the on-disk path for a key. */
    internal fun cacheFileForKey(context: Context, key: String): File = cacheFileBin(context, key)
}
