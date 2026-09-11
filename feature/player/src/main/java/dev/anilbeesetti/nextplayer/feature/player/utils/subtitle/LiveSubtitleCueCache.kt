package dev.anilbeesetti.nextplayer.feature.player.utils.subtitle

import android.content.Context
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.common.extensions.subtitleCacheDir
import dev.anilbeesetti.nextplayer.feature.player.model.TimedCue
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Session LRU + disk cache for live-subtitle [TimedCue] timelines.
 *
 * Disk files live under [Context.subtitleCacheDir] as `live_cues_*.json`, keyed by media URI /
 * media id, track signature, and optional file length/lastModified.
 */
@UnstableApi
object LiveSubtitleCueCache {

    private const val VERSION = 1
    private const val MEMORY_CAPACITY = 12
    private const val FILE_PREFIX = "live_cues_"

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
        val file = cacheFile(context, key)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("v") != VERSION) return null
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
    }

    private fun writeDisk(context: Context, key: String, cues: List<TimedCue>) {
        runCatching {
            val arr = JSONArray()
            for (cue in cues) {
                arr.put(
                    JSONObject()
                        .put("s", cue.startMs)
                        .put("e", cue.endMs)
                        .put("t", cue.text),
                )
            }
            val root = JSONObject().put("v", VERSION).put("cues", arr)
            val file = cacheFile(context, key)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    private fun cacheFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
            .take(40)
        return File(context.subtitleCacheDir, "$FILE_PREFIX$digest.json")
    }

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
