package com.reelcut.app.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.reelcut.app.data.Canvas
import com.reelcut.app.data.CanvasMath
import com.reelcut.app.data.Clip
import com.reelcut.app.engine.PreviewPlayer
import com.reelcut.app.engine.Thumbnails
import com.reelcut.app.platformSupportsBlur
import kotlin.math.min

/**
 * Draws the canvas exactly as export will frame it: the canvas shape, black or blurred background,
 * and the current clip fitted or filled, rotated and mirrored. In trim mode it shows the raw source.
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
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val ratio = if (trimMode || current == null) {
            current?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (maxW / maxH)
        } else {
            CanvasMath.aspectRatio(clips, canvas)
        }
        val boxW = min(maxW, maxH * ratio)
        val boxH = boxW / ratio

        Box(
            Modifier
                .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                .clipToBounds()
                .background(Color.Black),
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
        }
    }
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
