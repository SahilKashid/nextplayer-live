package dev.anilbeesetti.nextplayer.feature.player.state

import kotlin.math.abs

/**
 * Pinch scale and pan, independent of the player slot size.
 *
 * Pan is a fraction of the pannable range at the current scale, so the same
 * value stays inside the video area when the live-subtitles panel changes
 * that slot's width.
 */
internal object PlayerZoomPan {
    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 4f

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun clampFraction(fraction: Float): Float = fraction.coerceIn(-1f, 1f)

    fun maxPan(zoom: Float, sizePx: Int): Float = abs((zoom - 1f) * sizePx / 2f)

    fun fraction(offsetPx: Float, zoom: Float, sizePx: Int): Float {
        val max = maxPan(zoom, sizePx)
        if (max == 0f) return 0f
        return clampFraction(offsetPx / max)
    }

    fun offsetPx(fraction: Float, zoom: Float, sizePx: Int): Float =
        clampFraction(fraction) * maxPan(zoom, sizePx)
}
