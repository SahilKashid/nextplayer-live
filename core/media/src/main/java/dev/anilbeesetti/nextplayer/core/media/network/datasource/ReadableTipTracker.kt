package dev.anilbeesetti.nextplayer.core.media.network.datasource

import java.util.concurrent.ConcurrentHashMap

/**
 * Publishes the current readable download tip (and declared length) for incomplete local media
 * so extractors / seek maps can clamp seeks without re-probing the filesystem on every scrub.
 *
 * Keys are filesystem paths or URI strings (callers should use the same key they look up with).
 * Datasources update whenever they observe an effective [readableEnd]; clearing on close is
 * optional because ExoPlayer may reopen the same URI during a seek.
 */
object ReadableTipTracker {

    data class TipInfo(
        val readableEnd: Long,
        val declaredLength: Long,
    )

    private val tips = ConcurrentHashMap<String, TipInfo>()

    /**
     * Records the latest readable tip for [uriOrPath].
     * Ignores non-positive [readableEnd] (keeps any previous tip).
     */
    @JvmStatic
    fun update(uriOrPath: String, readableEnd: Long, declaredLength: Long = -1L) {
        if (uriOrPath.isEmpty() || readableEnd < 0L) return
        tips[uriOrPath] = TipInfo(
            readableEnd = readableEnd,
            declaredLength = declaredLength,
        )
    }

    /** Latest readable tip for [uriOrPath], or `-1` if unknown. */
    @JvmStatic
    fun tipFor(uriOrPath: String): Long = tips[uriOrPath]?.readableEnd ?: -1L

    /** Latest declared length for [uriOrPath], or `-1` if unknown. */
    @JvmStatic
    fun declaredFor(uriOrPath: String): Long = tips[uriOrPath]?.declaredLength ?: -1L

    /** Full tip info, or null if never published. */
    @JvmStatic
    fun infoFor(uriOrPath: String): TipInfo? = tips[uriOrPath]

    /** Removes a single key (optional; not required on datasource close). */
    @JvmStatic
    fun clear(uriOrPath: String) {
        tips.remove(uriOrPath)
    }

    /** Test helper / shutdown. */
    @JvmStatic
    fun clearAll() {
        tips.clear()
    }
}
