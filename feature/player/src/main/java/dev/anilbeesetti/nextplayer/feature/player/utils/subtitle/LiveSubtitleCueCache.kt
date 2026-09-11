package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.content.Context
import android.net.Uri
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * Session LRU + disk cache for live-subtitle [TimedCue] timelines.
 *
 * Disk files live under [Context.subtitleCacheDir] as `live_cues_*.bin` (compact binary).
 * Legacy `live_cues_*.json` files are still read once and rewritten as binary.
 */
@UnstableApi
object LiveSubtitleCueCache {

    private const val VERSION = 2
    private const val MEMORY_CAPACITY = 12
    private const val FILE_PREFIX = "live_cues_"
    private const val MAGIC = 0x4C534342 // 'LSCB'

    private val lock = ReentrantLock()
    private val memory = object : LinkedHashMap<String, List<TimedCue>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TimedCue>>?): Boolean =
            size > MEMORY_CAPACITY
    }

    fun buildKey(
        mediaId: String?,
        mediaUri: Uri?,
        externalUri: Uri?,
        format: Format?,
        textTrackIndex: Int,
        context: Context,
    ): String {
        val uriForMeta = externalUri ?: mediaUri
        val meta = uriFileMeta(context, uriForMeta)
        return listOf(
            mediaId.orEmpty(),
            mediaUri?.toString().orEmpty(),
            externalUri?.toString().orEmpty(),
            format?.id.orEmpty(),
            format?.language.orEmpty(),
            format?.label.orEmpty(),
            format?.sampleMimeType.orEmpty(),
            format?.codecs.orEmpty(),
            textTrackIndex.toString(),
            meta?.first?.toString().orEmpty(),
            meta?.second?.toString().orEmpty(),
        ).joinToString("|")
    }

    fun get(context: Context, key: String): List<TimedCue>? {
        lock.withLock {
            memory[key]?.let { return it }
        }
        val disk = readDisk(context, key) ?: return null
        lock.withLock {
            memory[key] = disk
        }
        return disk
    }

    fun put(context: Context, key: String, cues: List<TimedCue>) {
        lock.withLock {
            memory[key] = cues
        }
        writeDisk(context, key, cues)
    }

    fun getMemoryOnly(key: String): List<TimedCue>? = lock.withLock { memory[key] }

    private fun readDisk(context: Context, key: String): List<TimedCue>? {
        val bin = cacheFileBin(context, key)
        if (bin.exists() && bin.isFile) {
            readBinary(bin)?.let { return it }
        }
        val json = cacheFileJson(context, key)
        if (json.exists() && json.isFile) {
            val legacy = readJson(json) ?: return null
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
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
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
            buildList(count) {
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

    private fun uriFileMeta(context: Context, uri: Uri?): Pair<Long, Long>? {
        if (uri == null) return null
        return runCatching {
            when (uri.scheme) {
                "file" -> {
                    val path = uri.path ?: return null
                    val f = File(path)
                    if (!f.exists()) return null
                    f.length() to f.lastModified()
                }
                "content" -> {
                    context.contentResolver.query(
                        uri,
                        arrayOf(
                            android.provider.OpenableColumns.SIZE,
                            "last_modified",
                        ),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return null
                        val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        val modIdx = cursor.getColumnIndex("last_modified")
                        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
                        val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else -1L
                        if (size < 0 && mod < 0) null else size to mod
                    }
                }
                else -> null
            }
        }.getOrNull()
    }
}
