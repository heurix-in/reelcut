package com.reelcut.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import com.reelcut.app.data.Canvas
import com.reelcut.app.data.CanvasMath
import com.reelcut.app.data.Clip
import com.reelcut.app.data.Overlay
import com.reelcut.app.engine.PreviewPlayer
import com.reelcut.app.engine.Thumbnails
import com.reelcut.app.platformSupportsBlur
import com.reelcut.app.ui.editor.OverlayDraw.drawOverlay
import com.reelcut.app.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.min

/** What the preview needs to show and edit text/logo overlays. */
class OverlayState(
    val overlays: List<Overlay>,
    val timeUs: Long,
    val selectedId: String?,
    val measurer: TextMeasurer,
    val logos: Map<String, ImageBitmap>,
    /** Tap on an overlay (null = tap on empty video). */
    val onTap: (Overlay?) -> Unit,
    /** Live change while dragging, pinching or using the corner handle. */
    val onChange: (id: String, transform: (Overlay) -> Overlay) -> Unit,
)

/**
 * Draws the canvas exactly as export will frame it: the canvas shape, colour or blurred background,
 * the current clip fitted or filled, rotated and mirrored, and the text/logo overlays on top.
 * In trim mode it shows the raw source.
 */
@Composable
fun CanvasPreview(
    player: PreviewPlayer,
    clips: List<Clip>,
    canvas: Canvas,
    current: Clip?,
    sourceTimeUs: Long,
    trimMode: Boolean,
    thumbs: Thumbnails,
    overlays: OverlayState?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val ratio = if (trimMode || current == null) {
            current?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (maxW / maxH)
        } else {
            CanvasMath.aspectRatio(clips, canvas)
        }
        val boxW = min(maxW, maxH * ratio)
        val boxH = boxW / ratio
        val background = if (trimMode) Color.Black else OverlayDraw.color(canvas.backgroundArgb)

        Box(
            Modifier
                .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                .clipToBounds()
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            val clip = if (trimMode) current?.copy(rotation = 0, flipH = false, fill = false) else current

            if (!trimMode && clip != null && canvas.blurBackground && !clip.fill) {
                val bg by rememberFrame(thumbs, clip, sourceTimeUs, 240)
                bg?.let {
                    Image(
                        it, null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(blurOrDim()),
                    )
                }
            }

            val content = clip?.let { CanvasMath.contentSize(it, boxW, boxH) }
            val contentModifier = if (clip != null && content != null) {
                Modifier
                    .requiredSize(with(density) { content.width.toDp() }, with(density) { content.height.toDp() })
                    .graphicsLayer {
                        rotationZ = clip.rotation.toFloat()
                        scaleX = if (clip.flipH) -1f else 1f
                    }
            } else Modifier.fillMaxSize()

            Box(contentModifier) {
                player.Surface(Modifier.fillMaxSize())
                if (clip != null && clip.isPhoto && !trimMode) {
                    val photo by rememberFrame(thumbs, clip, 0L, 1280)
                    photo?.let {
                        Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            if (!trimMode && overlays != null) {
                OverlayLayer(overlays, boxW, boxH)
            }
        }
    }
}

/** Draws visible overlays and handles tap / drag / pinch / corner-handle editing. */
@Composable
private fun OverlayLayer(state: OverlayState, w: Float, h: Float) {
    val density = LocalDensity.current
    val handleRadius = with(density) { 14.dp.toPx() }
    val stateRef = rememberUpdatedState(state)

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(w, h) {
                awaitEachGesture {
                    val s0 = stateRef.value
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val selected = s0.overlays.firstOrNull { it.id == s0.selectedId }
                    val selBox = selected?.let {
                        OverlayDraw.boxSize(it, w, h, s0.measurer, s0.logos[it.imageUri])
                    }
                    // Where the gesture started decides what it does.
                    val handlePos = if (selected != null && selBox != null) {
                        OverlayDraw.toCanvas(selected, Offset(selBox.width / 2, selBox.height / 2), w, h)
                    } else null
                    val onHandle = handlePos != null && (down.position - handlePos).getDistance() <= handleRadius * 1.6f
                    val onSelected = selected != null && selBox != null &&
                        OverlayDraw.hit(selected, down.position, w, h, selBox, slop = handleRadius * 0.5f)

                    var moved = false
                    var travel = 0f
                    val startCentre = selected?.let { OverlayDraw.centre(it, w, h) }
                    val startVector = startCentre?.let { down.position - it }
                    val startScale = selected?.scale ?: 1f
                    val startRotation = selected?.rotation ?: 0f
                    var last = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val change = pressed.first()
                        val delta = change.positionChange()
                        travel += abs(delta.x) + abs(delta.y)
                        val sel = selected ?: continue
                        if (pressed.size >= 2 && onSelected) {
                            val zoom = event.calculateZoom()
                            val rot = event.calculateRotation()
                            val pan = event.calculatePan()
                            moved = true
                            stateRef.value.onChange(sel.id) { o ->
                                o.copy(
                                    scale = (o.scale * zoom).coerceIn(Overlay.MIN_SCALE, Overlay.MAX_SCALE),
                                    rotation = normaliseDegrees(o.rotation + rot),
                                    x = (o.x + pan.x / w).coerceIn(0f, 1f),
                                    y = (o.y + pan.y / h).coerceIn(0f, 1f),
                                )
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (!moved && travel < viewConfiguration.touchSlop) continue
                        if (onHandle && startCentre != null && startVector != null) {
                            moved = true
                            val v = change.position - startCentre
                            val ratio = v.getDistance() / startVector.getDistance().coerceAtLeast(1f)
                            val angle = OverlayDraw.angleDegrees(v) - OverlayDraw.angleDegrees(startVector)
                            stateRef.value.onChange(sel.id) { o ->
                                o.copy(
                                    scale = (startScale * ratio).coerceIn(Overlay.MIN_SCALE, Overlay.MAX_SCALE),
                                    rotation = normaliseDegrees(startRotation + angle),
                                )
                            }
                            change.consume()
                        } else if (onSelected) {
                            moved = true
                            val d = change.position - last
                            stateRef.value.onChange(sel.id) { o ->
                                o.copy(
                                    x = (o.x + d.x / w).coerceIn(0f, 1f),
                                    y = (o.y + d.y / h).coerceIn(0f, 1f),
                                )
                            }
                            change.consume()
                        }
                        last = change.position
                    }
                    if (!moved && travel < viewConfiguration.touchSlop) {
                        // A tap: pick the top-most visible overlay under the finger.
                        val s = stateRef.value
                        val hit = s.overlays.asReversed().firstOrNull { o ->
                            (o.visibleAt(s.timeUs) || o.id == s.selectedId) &&
                                OverlayDraw.hit(o, down.position, w, h, OverlayDraw.boxSize(o, w, h, s.measurer, s.logos[o.imageUri]))
                        }
                        s.onTap(hit)
                    }
                }
            }
    ) {
        state.overlays.forEach { o ->
            if (o.visibleAt(state.timeUs) || o.id == state.selectedId) {
                drawOverlay(o, w, h, state.measurer, state.logos[o.imageUri])
            }
        }
        // Selection frame and the corner handle (drag it to resize and rotate).
        val sel = state.overlays.firstOrNull { it.id == state.selectedId } ?: return@Canvas
        val box = OverlayDraw.boxSize(sel, w, h, state.measurer, state.logos[sel.imageUri])
        val corners = listOf(
            Offset(-box.width / 2, -box.height / 2),
            Offset(box.width / 2, -box.height / 2),
            Offset(box.width / 2, box.height / 2),
            Offset(-box.width / 2, box.height / 2),
        ).map { OverlayDraw.toCanvas(sel, it, w, h) }
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(
            path,
            color = Palette.Accent,
            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
        )
        drawCircle(Palette.Accent, radius = handleRadius, center = corners[2])
        drawCircle(Palette.Ink, radius = handleRadius * 0.38f, center = corners[2])
    }
}

private fun normaliseDegrees(d: Float): Float {
    var v = d % 360f
    if (v > 180f) v -= 360f
    if (v < -180f) v += 360f
    return v
}

@Composable
private fun rememberFrame(thumbs: Thumbnails, clip: Clip, timeUs: Long, heightPx: Int) =
    produceState<ImageBitmap?>(
        thumbs.peek(clip.sourceUri, timeUs, heightPx),
        clip.sourceUri, thumbs.quantize(timeUs), heightPx,
    ) {
        value = thumbs.get(clip.sourceUri, timeUs, heightPx) ?: value
    }

/** Real blur where the platform supports it (Android 12+ and Windows), a dimmed copy otherwise. */
private fun blurOrDim(): Modifier =
    if (platformSupportsBlur) Modifier.blur(28.dp) else Modifier.alpha(0.45f)
