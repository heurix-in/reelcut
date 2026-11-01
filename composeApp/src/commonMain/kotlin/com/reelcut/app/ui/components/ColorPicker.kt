package com.reelcut.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Type
import kotlin.math.max
import kotlin.math.min

/** Ready-made colours (ARGB). */
val SwatchColors: List<Long> = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF252525, 0xFF5C574D, 0xFF9E9E9E, 0xFFF4F4D7,
    0xFFEEEA7A, 0xFFFFC94D, 0xFFFF8A3D, 0xFFE53935, 0xFFFF5C8A, 0xFFB15CFF,
    0xFF5B6CFF, 0xFF2EA8FF, 0xFF26C6DA, 0xFF2ECC71, 0xFF1B5E20, 0xFF6D4C41,
)

fun argbOf(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

/**
 * A row of colour swatches plus two sliders (hue and shade) for any other colour.
 * [allowNone] adds a "none" swatch that reports 0.
 */
@Composable
fun ColorPicker(
    value: Long,
    onChange: (Long) -> Unit,
    allowNone: Boolean = false,
) {
    val onChangeState = rememberUpdatedState(onChange)
    var hue by remember { mutableFloatStateOf(hueOf(value)) }
    var shade by remember { mutableFloatStateOf(shadeOf(value)) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (allowNone) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Palette.InkRaised)
                        .border(2.dp, if (value == 0L) Palette.Accent else Palette.Line, CircleShape)
                        .clickable { onChange(0L) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Block, "None", tint = Palette.Muted, modifier = Modifier.size(18.dp))
                }
            }
            SwatchColors.forEach { c ->
                val selected = c == value
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(c.toInt()))
                        .border(if (selected) 3.dp else 1.dp, if (selected) Palette.Accent else Palette.Line, CircleShape)
                        .clickable {
                            hue = hueOf(c)
                            shade = shadeOf(c)
                            onChange(c)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check, null,
                            tint = if (isLight(c)) Palette.Ink else Palette.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Hue", style = Type.Label, color = Palette.Muted, modifier = Modifier.width(44.dp))
            GradientSlider(
                value = hue / 360f,
                brush = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }),
                modifier = Modifier.weight(1f),
            ) {
                hue = it * 360f
                onChangeState.value(colorFrom(hue, shade))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Shade", style = Type.Label, color = Palette.Muted, modifier = Modifier.width(44.dp))
            GradientSlider(
                value = shade,
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.hsv(hue, 1f, 1f), Color.White)
                ),
                modifier = Modifier.weight(1f),
            ) {
                shade = it
                onChangeState.value(colorFrom(hue, shade))
            }
        }
    }
}

/** 0 = black, 0.5 = full colour, 1 = white. */
private fun colorFrom(hue: Float, shade: Float): Long {
    val c = when {
        shade <= 0.5f -> Color.hsv(hue % 360f, 1f, (shade * 2f).coerceIn(0f, 1f))
        else -> Color.hsv(hue % 360f, (1f - (shade - 0.5f) * 2f).coerceIn(0f, 1f), 1f)
    }
    return argbOf(c)
}

private fun rgb(argb: Long): Triple<Float, Float, Float> {
    val v = argb.toInt()
    return Triple(((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f)
}

private fun hueOf(argb: Long): Float {
    if (argb == 0L) return 50f
    val (r, g, b) = rgb(argb)
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    if (d < 1e-4f) return 50f
    val h = when (mx) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return (h + 360f) % 360f
}

private fun shadeOf(argb: Long): Float {
    if (argb == 0L) return 0.5f
    val (r, g, b) = rgb(argb)
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val sat = if (mx <= 0f) 0f else (mx - mn) / mx
    return if (mx < 0.999f) mx / 2f else 0.5f + (1f - sat) / 2f
}

private fun isLight(argb: Long): Boolean {
    val (r, g, b) = rgb(argb)
    return 0.299f * r + 0.587f * g + 0.114f * b > 0.6f
}

/** A slider whose track is a colour gradient (tap or drag). */
@Composable
private fun GradientSlider(value: Float, brush: Brush, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val onChangeState = rememberUpdatedState(onChange)
    Canvas(
        modifier
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { onChangeState.value((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChangeState.value((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        val trackH = 10.dp.toPx()
        val top = (size.height - trackH) / 2
        drawRoundRect(brush, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, trackH), cornerRadius = CornerRadius(trackH / 2))
        val x = (value.coerceIn(0f, 1f) * size.width).coerceIn(trackH / 2, size.width - trackH / 2)
        drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(x, size.height / 2))
        drawCircle(Palette.Ink, radius = 11.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(2.dp.toPx()))
    }
}
