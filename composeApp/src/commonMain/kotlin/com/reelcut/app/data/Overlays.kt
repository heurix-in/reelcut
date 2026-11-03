package com.reelcut.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class OverlayKind { TEXT, LOGO }

/** New fonts are only ever added at the end: projects store the name. */
@Serializable
enum class TextFont(val label: String) {
    SANS("Clean"), BOLD("Bold"), SERIF("Serif"), MONO("Mono"),
    POSTER("Poster"), IMPACT("Impact"), ELEGANT("Elegant"), SCRIPT("Script"), HAND("Hand"), ROUND("Round"),
}

@Serializable
enum class TextAlignment { LEFT, CENTER, RIGHT }

/**
 * A text or logo layer over the video. Position and size are relative to the canvas, so the
 * preview and every export resolution place it the same way.
 */
@Serializable
data class Overlay(
    val id: String,
    val kind: OverlayKind,
    /** Timeline range where it shows. */
    val startUs: Long,
    val endUs: Long,
    /** Centre, 0..1 across and down the canvas. */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** 1 = default size (text: 7.5% of the canvas's short side; logo: 28% of it wide). */
    val scale: Float = 1f,
    /** Clockwise degrees. */
    val rotation: Float = 0f,
    val opacity: Float = 1f,

    // Text
    val text: String = "",
    val font: TextFont = TextFont.BOLD,
    val colorArgb: Long = 0xFFFFFFFF,
    /** 0 = no outline. */
    val outlineArgb: Long = 0,
    /** Outline thickness as a fraction of the font size. */
    val outlineWidth: Float = 0.08f,
    val shadow: Boolean = false,
    /** 0 = no box behind the text. */
    val labelArgb: Long = 0,
    val align: TextAlignment = TextAlignment.CENTER,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val uppercase: Boolean = false,
    /** Extra space between letters as a fraction of the font size (0 = normal). */
    val letterSpacing: Float = 0f,
    /** How it comes in and goes out (see [AnimMath]); applies to text and logos. */
    val animIn: OverlayAnim = OverlayAnim.NONE,
    val animOut: OverlayAnim = OverlayAnim.NONE,

    // Logo
    val imageUri: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
) {
    val isText: Boolean get() = kind == OverlayKind.TEXT

    fun visibleAt(timeUs: Long): Boolean = timeUs in startUs until endUs

    companion object {
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 6f
        const val DEFAULT_TEXT_US = 3_000_000L

        /** Size of an emoji sticker (it is text, scaled up). */
        const val STICKER_SCALE = 2.6f
        const val MIN_US = 100_000L
    }
}

object OverlayMath {
    /**
     * Puts each overlay on the first timeline lane where it doesn't overlap another one.
     * Returns lane index per overlay id.
     */
    fun lanes(overlays: List<Overlay>): Map<String, Int> {
        val laneEnds = mutableListOf<Long>()
        val out = HashMap<String, Int>()
        overlays.sortedWith(compareBy({ it.startUs }, { it.id })).forEach { o ->
            val lane = laneEnds.indexOfFirst { it <= o.startUs }.let { if (it < 0) laneEnds.size else it }
            if (lane == laneEnds.size) laneEnds += o.endUs else laneEnds[lane] = o.endUs
            out[o.id] = lane
        }
        return out
    }

    /** A new overlay's time range: from [playheadUs] for [lengthUs], kept inside the video. */
    fun rangeAt(playheadUs: Long, totalUs: Long, lengthUs: Long): Pair<Long, Long> {
        if (totalUs <= 0) return 0L to lengthUs
        val len = lengthUs.coerceAtMost(totalUs)
        val start = playheadUs.coerceIn(0L, (totalUs - len).coerceAtLeast(0L))
        return start to (start + len)
    }

    /** Keeps start < end, both inside 0..total (when known), at least [Overlay.MIN_US] long. */
    fun clampRange(startUs: Long, endUs: Long, totalUs: Long): Pair<Long, Long> {
        val max = if (totalUs > 0) totalUs else Long.MAX_VALUE
        val s = startUs.coerceIn(0L, (max - Overlay.MIN_US).coerceAtLeast(0L))
        val e = endUs.coerceIn(s + Overlay.MIN_US, max.coerceAtLeast(s + Overlay.MIN_US))
        return s to e
    }
}
