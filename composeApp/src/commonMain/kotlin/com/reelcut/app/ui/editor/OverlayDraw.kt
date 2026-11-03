package com.reelcut.app.ui.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.reelcut.app.BundledTextFonts
import com.reelcut.app.UrbanistFamily
import com.reelcut.app.data.AnimMath
import com.reelcut.app.data.AnimState
import com.reelcut.app.data.Overlay
import com.reelcut.app.data.OverlayAnim
import com.reelcut.app.data.OverlayKind
import com.reelcut.app.data.TextAlignment
import com.reelcut.app.data.TextFont
import com.reelcut.app.engine.MediaEngine
import com.reelcut.app.engine.OverlayLayer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws text and logo overlays. The preview and both exporters use these same functions, at
 * different sizes, so what you see is what you export. All sizes are in pixels of the canvas.
 */
object OverlayDraw {
    private val PixelDensity = Density(1f)

    fun color(argb: Long): Color = Color(argb.toInt())

    fun fontPx(o: Overlay, w: Float, h: Float): Float = min(w, h) * 0.075f * o.scale

    /** The font family and weight for a text style. The bundled ones are cut to one weight, so they ask for Normal. */
    fun fontFor(font: TextFont): Pair<FontFamily, FontWeight> = when (font) {
        TextFont.SANS -> UrbanistFamily to FontWeight.Medium
        TextFont.BOLD -> UrbanistFamily to FontWeight.Bold
        TextFont.SERIF -> FontFamily.Serif to FontWeight.Bold
        TextFont.MONO -> FontFamily.Monospace to FontWeight.Bold
        else -> (BundledTextFonts[font] ?: UrbanistFamily) to FontWeight.Normal
    }

    fun layoutText(o: Overlay, w: Float, h: Float, measurer: TextMeasurer): TextLayoutResult {
        val px = fontPx(o, w, h)
        val (fam, weight) = fontFor(o.font)
        val style = TextStyle(
            fontFamily = fam,
            fontWeight = weight,
            fontStyle = if (o.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (o.underline) TextDecoration.Underline else TextDecoration.None,
            letterSpacing = o.letterSpacing.em,
            fontSize = px.sp, // density 1: 1 sp == 1 px
            lineHeight = (px * 1.15f).sp,
            textAlign = when (o.align) {
                TextAlignment.LEFT -> TextAlign.Left
                TextAlignment.CENTER -> TextAlign.Center
                TextAlignment.RIGHT -> TextAlign.Right
            },
        )
        return measurer.measure(
            text = AnnotatedString((if (o.uppercase) o.text.uppercase() else o.text).ifEmpty { " " }),
            style = style,
            constraints = Constraints(maxWidth = max(1, (w * 0.92f).roundToInt())),
            density = PixelDensity,
            layoutDirection = LayoutDirection.Ltr,
        )
    }

    private fun textPad(o: Overlay, px: Float) = if (o.labelArgb != 0L) px * 0.35f else px * 0.12f

    /** Unrotated size of the overlay's box. */
    fun boxSize(o: Overlay, w: Float, h: Float, measurer: TextMeasurer, logo: ImageBitmap?): Size =
        if (o.kind == OverlayKind.TEXT) {
            val layout = layoutText(o, w, h, measurer)
            val pad = textPad(o, fontPx(o, w, h))
            Size(layout.size.width + pad * 2, layout.size.height + pad * 2)
        } else {
            val lw = min(w, h) * 0.28f * o.scale
            val iw = (logo?.width ?: o.imageWidth).coerceAtLeast(1)
            val ih = (logo?.height ?: o.imageHeight).coerceAtLeast(1)
            Size(lw, lw * ih / iw)
        }

    fun centre(o: Overlay, w: Float, h: Float) = Offset(o.x * w, o.y * h)

    /** True if canvas point [p] falls inside the (rotated) overlay box. */
    fun hit(o: Overlay, p: Offset, w: Float, h: Float, box: Size, slop: Float = 0f): Boolean {
        val local = toLocal(o, p, w, h)
        return kotlin.math.abs(local.x) <= box.width / 2 + slop && kotlin.math.abs(local.y) <= box.height / 2 + slop
    }

    /** Point in the overlay's own unrotated frame, origin at its centre. */
    fun toLocal(o: Overlay, p: Offset, w: Float, h: Float): Offset {
        val d = p - centre(o, w, h)
        val a = -o.rotation * (kotlin.math.PI / 180.0)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return Offset(d.x * c - d.y * s, d.x * s + d.y * c)
    }

    /** Canvas position of a point given in the overlay's own frame. */
    fun toCanvas(o: Overlay, local: Offset, w: Float, h: Float): Offset {
        val a = o.rotation * (kotlin.math.PI / 180.0)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return centre(o, w, h) + Offset(local.x * c - local.y * s, local.x * s + local.y * c)
    }

    fun angleDegrees(v: Offset): Float = (atan2(v.y, v.x) * 180.0 / kotlin.math.PI).toFloat()

    /**
     * Draws one overlay onto a canvas of [w] x [h] pixels. With [timeUs] (timeline time) an animated overlay is
     * drawn where its animation has it at that moment; without it, at rest (as the export layers are drawn).
     */
    fun DrawScope.drawOverlay(o: Overlay, w: Float, h: Float, measurer: TextMeasurer, logo: ImageBitmap?, timeUs: Long? = null) {
        val animated = timeUs != null && o.visibleAt(timeUs) && (o.animIn != OverlayAnim.NONE || o.animOut != OverlayAnim.NONE)
        val s = if (animated) AnimMath.state(o, timeUs!!) else AnimState.Rest
        if (s.alpha <= 0f) return
        val shown = if (s.alpha < 1f) o.copy(opacity = o.opacity * s.alpha) else o
        val c = centre(o, w, h) + Offset(s.dx * min(w, h), s.dy * min(w, h))
        translate(c.x, c.y) {
            rotate(o.rotation, pivot = Offset.Zero) {
                scale(s.scale, pivot = Offset.Zero) {
                    if (o.kind == OverlayKind.TEXT) drawTextBox(shown, w, h, measurer) else drawLogo(shown, w, h, logo)
                }
            }
        }
    }

    private fun DrawScope.drawTextBox(o: Overlay, w: Float, h: Float, measurer: TextMeasurer) {
        if (o.text.isBlank()) return
        val px = fontPx(o, w, h)
        val layout = layoutText(o, w, h, measurer)
        val pad = textPad(o, px)
        val bw = layout.size.width + pad * 2
        val bh = layout.size.height + pad * 2
        val topLeft = Offset(-bw / 2, -bh / 2)
        val alpha = o.opacity.coerceIn(0f, 1f)
        if (o.labelArgb != 0L) {
            drawRoundRect(
                color = color(o.labelArgb),
                topLeft = topLeft,
                size = Size(bw, bh),
                cornerRadius = CornerRadius(px * 0.3f),
                alpha = alpha,
            )
        }
        val textAt = topLeft + Offset(pad, pad)
        if (o.outlineArgb != 0L && o.outlineWidth > 0f) {
            drawText(
                textLayoutResult = layout,
                color = color(o.outlineArgb),
                topLeft = textAt,
                alpha = alpha,
                drawStyle = Stroke(width = px * o.outlineWidth * 2f),
            )
        }
        drawText(
            textLayoutResult = layout,
            color = color(o.colorArgb),
            topLeft = textAt,
            alpha = alpha,
            shadow = if (o.shadow) Shadow(Color(0x99000000), Offset(px * 0.06f, px * 0.08f), px * 0.18f) else null,
        )
    }

    private fun DrawScope.drawLogo(o: Overlay, w: Float, h: Float, logo: ImageBitmap?) {
        val image = logo ?: return
        val lw = min(w, h) * 0.28f * o.scale
        val lh = lw * image.height / image.width.coerceAtLeast(1)
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((-lw / 2).roundToInt(), (-lh / 2).roundToInt()),
            dstSize = IntSize(lw.roundToInt().coerceAtLeast(1), lh.roundToInt().coerceAtLeast(1)),
            alpha = o.opacity.coerceIn(0f, 1f),
        )
    }

    /** Draws one overlay into a new transparent image the size of the export frame. */
    fun renderLayer(o: Overlay, width: Int, height: Int, measurer: TextMeasurer, logo: ImageBitmap?): ImageBitmap {
        val bitmap = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        val canvas = Canvas(bitmap)
        CanvasDrawScope().draw(PixelDensity, LayoutDirection.Ltr, canvas, Size(width.toFloat(), height.toFloat())) {
            drawOverlay(o, width.toFloat(), height.toFloat(), measurer, logo)
        }
        return bitmap
    }
}

/** Turns the project's overlays into export layers. Created in the UI, where a [TextMeasurer] is available. */
class OverlayRenderer(private val measurer: TextMeasurer, private val engine: MediaEngine) {
    suspend fun render(overlays: List<Overlay>, width: Int, height: Int): List<OverlayLayer> =
        overlays.mapNotNull { o ->
            if (o.kind == OverlayKind.TEXT && o.text.isBlank()) return@mapNotNull null
            val logo = if (o.kind == OverlayKind.LOGO) engine.loadImage(o.imageUri, 2048) ?: return@mapNotNull null else null
            OverlayLayer(
                OverlayDraw.renderLayer(o, width, height, measurer, logo), o.startUs, o.endUs,
                o.animIn, o.animOut, o.x, o.y,
            )
        }
}
