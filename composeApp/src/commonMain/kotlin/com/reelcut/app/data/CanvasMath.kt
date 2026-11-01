package com.reelcut.app.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure geometry for the canvas: used by the preview layout and by export, so both agree. */
object CanvasMath {

    data class Size(val width: Int, val height: Int)

    data class SizeF(val width: Float, val height: Float)

    /** Width / height of the canvas. "Original" follows the first clip (after the user's rotation). */
    fun aspectRatio(clips: List<Clip>, canvas: Canvas): Float {
        if (canvas.aspect != Aspect.ORIGINAL) return canvas.aspect.w.toFloat() / canvas.aspect.h
        val first = clips.firstOrNull { it.width > 0 && it.height > 0 } ?: return 9f / 16f
        return first.rotatedWidth.toFloat() / first.rotatedHeight
    }

    /** Export frame size with [shortSide] pixels on the short side, both sides even. */
    fun outputSize(clips: List<Clip>, canvas: Canvas, shortSide: Int): Size {
        val ratio = aspectRatio(clips, canvas)
        val (w, h) = if (ratio >= 1f) {
            (shortSide * ratio).roundToInt() to shortSide
        } else {
            shortSide to (shortSide / ratio).roundToInt()
        }
        return Size(even(w), even(h))
    }

    /**
     * Size of the (unrotated) content when placed in a [boxW] x [boxH] canvas.
     * Rotation is applied afterwards around the centre, so fit/fill uses the rotated shape.
     */
    fun contentSize(clip: Clip, boxW: Float, boxH: Float): SizeF {
        val cw = clip.width.takeIf { it > 0 }?.toFloat() ?: boxW
        val ch = clip.height.takeIf { it > 0 }?.toFloat() ?: boxH
        val rw = if (clip.rotation % 180 == 0) cw else ch
        val rh = if (clip.rotation % 180 == 0) ch else cw
        val scale = if (clip.fill) max(boxW / rw, boxH / rh) else min(boxW / rw, boxH / rh)
        return SizeF(cw * scale, ch * scale)
    }

    /** Video bitrate in bits per second for the chosen quality. */
    fun bitrate(size: Size, settings: ExportSettings): Int {
        val bitsPerPixel = when (settings.quality) {
            Quality.LOW -> 0.05
            Quality.MEDIUM -> 0.08
            Quality.HIGH -> 0.12
        }
        return (size.width.toDouble() * size.height * settings.fps * bitsPerPixel)
            .toInt()
            .coerceIn(1_000_000, 40_000_000)
    }

    private fun even(v: Int) = max(2, (v / 2) * 2)
}
