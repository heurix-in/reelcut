package com.reelcut.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.reelcut.app.data.Clip
import com.reelcut.app.engine.Thumbnails
import com.reelcut.app.ui.components.AccentButton
import com.reelcut.app.ui.components.GhostButton
import com.reelcut.app.ui.components.RoundButton
import com.reelcut.app.ui.components.RoundStyle
import com.reelcut.app.ui.components.formatTime
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

private val PanelShape = RoundedCornerShape(28.dp)

@Composable
fun FloatingPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(PanelShape)
            .background(Palette.GlassStrong)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

// ---------------- Trim ----------------

/** Shows the whole source video; yellow handles set where the clip starts and ends. */
@Composable
fun TrimPanel(
    clip: Clip,
    range: Pair<Long, Long>,
    isPlaying: Boolean,
    thumbs: Thumbnails,
    positionUs: () -> Long,
    onChange: (start: Long, end: Long, movingStart: Boolean, exact: Boolean) -> Unit,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val (start, end) = range
    FloatingPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trim", style = Type.Title)
            Spacer(Modifier.weight(1f))
            Text(formatTime(end - start, tenths = true), style = Type.Label, color = Palette.Accent)
        }
        TrimBar(clip, range, thumbs, positionUs, onChange)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(start, tenths = true), style = Type.Label, color = Palette.Muted)
            Spacer(Modifier.weight(1f))
            RoundButton(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                onPlay,
                style = RoundStyle.White,
            )
            Spacer(Modifier.weight(1f))
            Text(formatTime(end, tenths = true), style = Type.Label, color = Palette.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Cancel", onCancel, Modifier.weight(1f))
            AccentButton("Done", onDone, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrimBar(
    clip: Clip,
    range: Pair<Long, Long>,
    thumbs: Thumbnails,
    positionUs: () -> Long,
    onChange: (start: Long, end: Long, movingStart: Boolean, exact: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val barHeight = 56.dp
    val handleW = 16.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(barHeight)
    ) {
        val handlePx = with(density) { handleW.toPx() }
        // Frames sit between the two handle widths so the handles never cover the ends.
        val trackPx = constraints.maxWidth - 2 * handlePx
        val duration = clip.sourceDurationUs.coerceAtLeast(1L)
        val usPerPx = duration / trackPx.toDouble()
        val xOf = { us: Long -> handlePx + (us / usPerPx).toFloat() }
        val heightPx = with(density) { barHeight.roundToPx() }

        val rangeState = rememberUpdatedState(range)
        val usPerPxState = rememberUpdatedState(usPerPx)
        val onChangeState = rememberUpdatedState(onChange)

        // Filmstrip of the whole source.
        val frameW = heightPx * 0.8f
        val count = ceil(trackPx / frameW).toInt().coerceAtLeast(1)
        Row(
            Modifier
                .offset { IntOffset(handlePx.roundToInt(), 0) }
                .width(with(density) { trackPx.toDp() })
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.Stone)
        ) {
            for (k in 0 until count) {
                val t = ((k + 0.5) * duration / count).toLong()
                ThumbFrame(
                    thumbs, clip.sourceUri, thumbs.quantize(t), heightPx,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }

        val (start, end) = range
        val sx = xOf(start)
        val ex = xOf(end)
        // Dim what is cut away.
        Box(
            Modifier
                .offset { IntOffset(handlePx.roundToInt(), 0) }
                .width(with(density) { (sx - handlePx).coerceAtLeast(0f).toDp() })
                .fillMaxHeight()
                .background(Color(0xB3000000))
        )
        Box(
            Modifier
                .offset { IntOffset(ex.roundToInt(), 0) }
                .width(with(density) { (constraints.maxWidth - handlePx - ex).coerceAtLeast(0f).toDp() })
                .fillMaxHeight()
                .background(Color(0xB3000000))
        )
        // Yellow frame between the handles.
        Box(
            Modifier
                .offset { IntOffset(sx.roundToInt(), 0) }
                .width(with(density) { (ex - sx).toDp() })
                .height(3.dp)
                .background(Palette.Accent)
        )
        Box(
            Modifier
                .offset { IntOffset(sx.roundToInt(), heightPx - with(density) { 3.dp.roundToPx() }) }
                .width(with(density) { (ex - sx).toDp() })
                .height(3.dp)
                .background(Palette.Accent)
        )

        // Playback position inside the source.
        var pos by remember { mutableLongStateOf(positionUs()) }
        LaunchedEffect(Unit) {
            while (true) {
                pos = positionUs()
                delay(33)
            }
        }
        Box(
            Modifier
                .offset { IntOffset((xOf(pos) - 1).roundToInt(), 0) }
                .width(2.dp)
                .fillMaxHeight()
                .background(Palette.White)
        )

        // Handles: each drag starts from the handle's value at touch-down.
        TrimHandle(
            Modifier.offset { IntOffset((sx - handlePx).roundToInt(), 0) },
            handleW,
            current = { rangeState.value.first },
            usPerPx = { usPerPxState.value },
            onValue = { us, done -> onChangeState.value(us, rangeState.value.second, true, done) },
        )
        TrimHandle(
            Modifier.offset { IntOffset(ex.roundToInt(), 0) },
            handleW,
            current = { rangeState.value.second },
            usPerPx = { usPerPxState.value },
            onValue = { us, done -> onChangeState.value(rangeState.value.first, us, false, done) },
        )
    }
}

@Composable
private fun TrimHandle(
    modifier: Modifier,
    width: androidx.compose.ui.unit.Dp,
    current: () -> Long,
    usPerPx: () -> Double,
    onValue: (us: Long, done: Boolean) -> Unit,
) {
    val currentState = rememberUpdatedState(current)
    val usPerPxState = rememberUpdatedState(usPerPx)
    val onValueState = rememberUpdatedState(onValue)
    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Accent)
            .pointerInput(Unit) {
                var base = 0L
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        base = currentState.value()
                        acc = 0f
                    },
                    onDragEnd = { onValueState.value(base + (acc * usPerPxState.value()).toLong(), true) },
                    onDragCancel = { onValueState.value(base + (acc * usPerPxState.value()).toLong(), true) },
                ) { change, dx ->
                    change.consume()
                    acc += dx
                    onValueState.value(base + (acc * usPerPxState.value()).toLong(), false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Palette.Ink)
        )
    }
}

// ---------------- Reorder ----------------

@Composable
fun ReorderPanel(
    clips: List<Clip>,
    thumbs: Thumbnails,
    onMove: (from: Int, to: Int) -> Unit,
    onDone: () -> Unit,
) {
    FloatingPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reorder", style = Type.Title)
            Spacer(Modifier.weight(1f))
            Text("Hold a clip, then drag", style = Type.Label, color = Palette.Muted)
        }
        ReorderRow(clips, thumbs, onMove)
        AccentButton("Done", onDone, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ReorderRow(clips: List<Clip>, thumbs: Thumbnails, onMove: (Int, Int) -> Unit) {
    val density = LocalDensity.current
    val tile = 68.dp
    val spacing = 8.dp
    val stepPx = with(density) { (tile + spacing).toPx() }
    val tilePx = with(density) { tile.roundToPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    val clipsState = rememberUpdatedState(clips)
    val onMoveState = rememberUpdatedState(onMove)
    val target = if (dragging >= 0) {
        (dragging + (offset / stepPx).roundToInt()).coerceIn(0, clips.lastIndex)
    } else -1

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        clips.forEachIndexed { i, clip ->
            val shift = when {
                dragging < 0 || i == dragging -> 0f
                i in (dragging + 1)..target -> -stepPx
                i in target until dragging -> stepPx
                else -> 0f
            }
            Box(
                Modifier
                    .zIndex(if (i == dragging) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (i == dragging) offset else shift
                        val s = if (i == dragging) 1.08f else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .size(tile)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.Stone)
                    .pointerInput(clip.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = clipsState.value.indexOfFirst { it.id == clip.id }
                                offset = 0f
                            },
                            onDragEnd = {
                                val from = dragging
                                val to = (from + (offset / stepPx).roundToInt()).coerceIn(0, clipsState.value.lastIndex)
                                dragging = -1
                                offset = 0f
                                if (from >= 0 && from != to) onMoveState.value(from, to)
                            },
                            onDragCancel = {
                                dragging = -1
                                offset = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            offset += amount.x
                        }
                    }
            ) {
                ThumbFrame(thumbs, clip.sourceUri, clip.trimStartUs, tilePx, Modifier.size(tile))
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Glass)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("${i + 1}", style = Type.Small)
                }
            }
        }
    }
}
