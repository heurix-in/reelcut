package com.reelcut.app.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.reelcut.app.data.Clip
import com.reelcut.app.data.Timeline
import com.reelcut.app.media.ThumbnailCache
import com.reelcut.app.ui.components.Pill
import com.reelcut.app.ui.components.formatTime
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Shape
import com.reelcut.app.ui.theme.Type
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val StripHeight = 60.dp
private val ClipHeight = 48.dp
private val ClipGap = 3.dp

/**
 * Filmstrip timeline with a fixed centre playhead. Drag to scrub, pinch to zoom,
 * tap a clip to select it, tap a "+" between clips to insert there.
 */
@Composable
fun TimelineStrip(
    clips: List<Clip>,
    missing: Set<String>,
    playheadUs: Long,
    selectedId: String?,
    thumbs: ThumbnailCache,
    dpPerSecond: Float,
    onZoom: (Float) -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (timeUs: Long, exact: Boolean) -> Unit,
    onSelect: (String?) -> Unit,
    onInsertAt: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxPerUs = with(density) { dpPerSecond.dp.toPx() } / 1_000_000f
    val starts = remember(clips) { Timeline.startsUs(clips) }
    val total = remember(clips) { Timeline.totalUs(clips) }

    val playheadState = rememberUpdatedState(playheadUs)
    val pxPerUsState = rememberUpdatedState(pxPerUs)
    val dpPerSecondState = rememberUpdatedState(dpPerSecond)
    val clipsState = rememberUpdatedState(clips)
    val totalState = rememberUpdatedState(total)
    val onScrubState = rememberUpdatedState(onScrub)
    val onScrubStartState = rememberUpdatedState(onScrubStart)
    val onSelectState = rememberUpdatedState(onSelect)
    val onZoomState = rememberUpdatedState(onZoom)

    BoxWithConstraints(
        modifier
            .height(StripHeight)
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var pending = 0f
                    var timeUs = playheadState.value.toDouble()
                    var zoom = dpPerSecondState.value
                    var upConsumed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) {
                            upConsumed = event.changes.any { it.isConsumed }
                            break
                        }
                        if (event.changes.count { it.pressed } > 1) {
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                if (!dragging) onScrubStartState.value()
                                dragging = true
                                zoom = (zoom * z).coerceIn(12f, 900f)
                                onZoomState.value(zoom)
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val dx = event.calculatePan().x
                        if (!dragging) {
                            pending += dx
                            if (abs(pending) > viewConfiguration.touchSlop) {
                                dragging = true
                                onScrubStartState.value()
                                timeUs = playheadState.value.toDouble()
                                timeUs -= pending / pxPerUsState.value
                            }
                        } else {
                            timeUs -= dx / pxPerUsState.value
                        }
                        if (dragging) {
                            timeUs = timeUs.coerceIn(0.0, totalState.value.toDouble())
                            onScrubState.value(timeUs.toLong(), false)
                            event.changes.forEach { it.consume() }
                        }
                    }
                    if (dragging) {
                        onScrubState.value(timeUs.toLong(), true)
                    } else if (!upConsumed) {
                        // Tap: select the clip under the finger.
                        val centre = size.width / 2f
                        val t = playheadState.value + ((down.position.x - centre) / pxPerUsState.value).toLong()
                        val list = clipsState.value
                        val hit = if (t < 0 || t > totalState.value) null else Timeline.locate(list, t)
                        onSelectState.value(hit?.let { list[it.index].id })
                    }
                }
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val centre = widthPx / 2f
        val gapPx = with(density) { ClipGap.toPx() }
        val clipHeightPx = with(density) { ClipHeight.roundToPx() }
        val topPx = with(density) { ((StripHeight - ClipHeight) / 2).roundToPx() }

        clips.forEachIndexed { i, clip ->
            val x = centre + (starts[i] - playheadUs) * pxPerUs
            val w = clip.durationUs * pxPerUs
            if (x + w >= -40f && x <= widthPx + 40f) {
                key(clip.id) {
                    ClipView(
                        clip = clip,
                        xPx = x + gapPx,
                        widthPx = (w - 2 * gapPx).coerceAtLeast(2f),
                        viewportWidthPx = widthPx,
                        pxPerUs = pxPerUs,
                        heightPx = clipHeightPx,
                        topPx = topPx,
                        selected = clip.id == selectedId,
                        missing = clip.id in missing,
                        thumbs = thumbs,
                    )
                }
            }
        }

        // "+" between clips: insert new videos at that point.
        val plusSize = 22.dp
        val plusPx = with(density) { plusSize.toPx() }
        for (i in 1 until clips.size) {
            val x = centre + (starts[i] - playheadUs) * pxPerUs
            if (x in -plusPx..(widthPx + plusPx)) {
                Box(
                    Modifier
                        .offset { IntOffset((x - plusPx / 2).roundToInt(), ((StripHeight.toPx() - plusPx) / 2).roundToInt()) }
                        .size(plusSize)
                        .clip(CircleShape)
                        .background(Palette.Ink)
                        .clickable { onInsertAt(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, "Insert clip here", tint = Palette.White, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Playhead: thin yellow line with a dot on top.
        val lineX = with(density) { centre.toDp() }
        Box(
            Modifier
                .offset(x = lineX - 1.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(Palette.Accent)
        )
        Box(
            Modifier
                .offset(x = lineX - 5.dp, y = 0.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(Palette.Accent)
        )
    }
}

@Composable
private fun ClipView(
    clip: Clip,
    xPx: Float,
    widthPx: Float,
    viewportWidthPx: Float,
    pxPerUs: Float,
    heightPx: Int,
    topPx: Int,
    selected: Boolean,
    missing: Boolean,
    thumbs: ThumbnailCache,
) {
    val density = LocalDensity.current
    val frameWidthPx = heightPx * 0.8f
    Box(
        Modifier
            .offset { IntOffset(xPx.roundToInt(), topPx) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .clip(Shape.ClipPill)
            .background(if (missing) Palette.Danger.copy(alpha = 0.5f) else Palette.Stone)
            .then(if (selected) Modifier.border(2.dp, Palette.Accent, Shape.ClipPill) else Modifier)
    ) {
        if (missing) {
            Text(
                "Media missing",
                style = Type.Small,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // Only the frames that are on screen.
            val visibleFrom = max(0f, -xPx)
            val visibleTo = min(widthPx, viewportWidthPx - xPx)
            if (visibleTo > visibleFrom) {
                val first = floor(visibleFrom / frameWidthPx).toInt()
                val last = ceil(visibleTo / frameWidthPx).toInt()
                val frameW = with(density) { frameWidthPx.toDp() }
                for (k in first..last) {
                    val localX = k * frameWidthPx
                    if (localX >= widthPx) break
                    val timeUs = Timeline.sourceTimeUs(clip, ((localX + frameWidthPx / 2) / pxPerUs).toLong())
                        .coerceAtMost(clip.trimEndUs - 1)
                    val q = thumbs.quantize(timeUs)
                    key(q, k) {
                        ThumbFrame(
                            thumbs = thumbs,
                            uri = clip.sourceUri,
                            timeUs = q,
                            heightPx = heightPx,
                            modifier = Modifier
                                .offset { IntOffset(localX.roundToInt(), 0) }
                                .width(frameW)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Palette.Accent, Shape.ClipPill)
                )
            }
            val badge = buildList {
                if (!clip.isPhoto && clip.speed != 1f) add("${clip.speed}×".replace(".0×", "×"))
                if (!clip.isPhoto && clip.volume == 0f) add("muted")
                if (clip.isPhoto) add("photo")
            }.joinToString(" · ")
            if (badge.isNotEmpty() && widthPx > 60f) {
                Text(
                    badge,
                    style = Type.Small,
                    color = Palette.Ink,
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(Shape.Pill)
                        .background(Palette.Accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun ThumbFrame(
    thumbs: ThumbnailCache,
    uri: String,
    timeUs: Long,
    heightPx: Int,
    modifier: Modifier = Modifier,
) {
    val image by produceState<ImageBitmap?>(thumbs.peek(uri, timeUs, heightPx), uri, timeUs, heightPx) {
        if (value == null) value = thumbs.get(uri, timeUs, heightPx)
    }
    val img = image
    if (img != null) {
        Image(img, null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(Palette.Stone))
    }
}

/** One chip per clip with its time range; the chip under the playhead is yellow. */
@Composable
fun TimeChips(
    clips: List<Clip>,
    playheadUs: Long,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val starts = remember(clips) { Timeline.startsUs(clips) }
    val current = Timeline.locate(clips, playheadUs)?.index ?: -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        itemsIndexed(clips, key = { _, c -> c.id }) { i, c ->
            val start = starts[i]
            Pill(
                text = "${formatTime(start)} · · · ${formatTime(start + c.durationUs)}",
                selected = i == current,
                selectedColor = Palette.Accent,
                onClick = { onJump(start) },
                style = Type.Small,
                padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
