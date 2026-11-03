package com.reelcut.app.data

import kotlinx.serialization.Serializable
import kotlin.math.pow

/** How a text or logo comes in, or goes out. Slides name the direction it moves. */
@Serializable
enum class OverlayAnim(val label: String) {
    NONE("None"), FADE("Fade"),
    SLIDE_UP("Slide up"), SLIDE_DOWN("Slide down"), SLIDE_LEFT("Slide left"), SLIDE_RIGHT("Slide right"),
    POP("Pop"),
}

/**
 * Where an animated overlay is at one moment: how opaque, how far it has moved ([dx], [dy] as fractions of
 * the canvas's short side, y down) and how big ([scale], about the overlay's own centre).
 */
data class AnimState(val alpha: Float, val dx: Float, val dy: Float, val scale: Float) {
    companion object {
        val Rest = AnimState(1f, 0f, 0f, 1f)
    }
}

/**
 * The motion of an animated overlay. The preview, the Android export and the Windows export all follow
 * this one definition: the fade is linear, slides ease out when coming in and ease in when leaving, and
 * pop grows from [POP_FROM] to full size (and back down when leaving).
 */
object AnimMath {
    const val MAX_US = 500_000L
    const val SLIDE_DISTANCE = 0.12f
    const val POP_FROM = 0.6f

    /** Length of each in/out animation for an overlay shown from [startUs] to [endUs]: at most half its time. */
    fun durationUs(startUs: Long, endUs: Long): Long = minOf(MAX_US, (endUs - startUs) / 2).coerceAtLeast(1L)

    /** Unit direction a slide moves in, as (x, y) with y down. */
    fun direction(a: OverlayAnim): Pair<Float, Float> = when (a) {
        OverlayAnim.SLIDE_UP -> 0f to -1f
        OverlayAnim.SLIDE_DOWN -> 0f to 1f
        OverlayAnim.SLIDE_LEFT -> -1f to 0f
        OverlayAnim.SLIDE_RIGHT -> 1f to 0f
        else -> 0f to 0f
    }

    /** 0 → 1 over the first [durationUs] after [startUs]; 1 once it is over. */
    private fun progress(tUs: Long, startUs: Long, durationUs: Long): Float =
        ((tUs - startUs).toFloat() / durationUs).coerceIn(0f, 1f)

    private fun easeOut(p: Float): Float = 1f - (1f - p).pow(3)
    private fun easeIn(p: Float): Float = p.pow(3)

    fun state(animIn: OverlayAnim, animOut: OverlayAnim, startUs: Long, endUs: Long, tUs: Long): AnimState {
        val d = durationUs(startUs, endUs)
        var alpha = 1f
        var dx = 0f
        var dy = 0f
        var scale = 1f
        if (animIn != OverlayAnim.NONE) {
            val p = progress(tUs, startUs, d)
            alpha *= p
            val (vx, vy) = direction(animIn)
            dx += -vx * SLIDE_DISTANCE * (1f - easeOut(p))
            dy += -vy * SLIDE_DISTANCE * (1f - easeOut(p))
            if (animIn == OverlayAnim.POP) scale *= POP_FROM + (1f - POP_FROM) * easeOut(p)
        }
        if (animOut != OverlayAnim.NONE) {
            // 0 while it is shown normally, 1 at the very end.
            val leaving = 1f - progress(tUs, endUs - d, d)
            val r = 1f - leaving
            alpha *= leaving
            val (vx, vy) = direction(animOut)
            dx += vx * SLIDE_DISTANCE * easeIn(r)
            dy += vy * SLIDE_DISTANCE * easeIn(r)
            if (animOut == OverlayAnim.POP) scale *= 1f - (1f - POP_FROM) * easeIn(r)
        }
        return AnimState(alpha, dx, dy, scale)
    }

    fun state(o: Overlay, tUs: Long): AnimState = state(o.animIn, o.animOut, o.startUs, o.endUs, tUs)
}
