package com.reelcut.app.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reelcut.app.data.Aspect
import com.reelcut.app.data.Canvas
import com.reelcut.app.data.Clip
import com.reelcut.app.data.Timeline
import com.reelcut.app.ui.components.AccentButton
import com.reelcut.app.ui.components.GhostButton
import com.reelcut.app.ui.components.Pill
import com.reelcut.app.ui.components.RoundButton
import com.reelcut.app.ui.components.RoundStyle
import com.reelcut.app.ui.components.formatTime
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Type
import kotlin.math.roundToInt

@Composable
private fun PanelHeader(title: String, value: String? = null, onApplyAll: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.Title)
        if (value != null) {
            Text("  $value", style = Type.Label, color = Palette.Accent)
        }
        Spacer(Modifier.weight(1f))
        if (onApplyAll != null) Pill("Apply to all", onClick = onApplyAll)
    }
}

@Composable
private fun PanelActions(onCancel: () -> Unit, onDone: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("Cancel", onCancel, Modifier.weight(1f))
        AccentButton("Done", onDone, Modifier.weight(1f))
    }
}

@Composable
private fun AccentSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = Palette.Accent,
            activeTrackColor = Palette.Accent,
            inactiveTrackColor = Palette.Stone,
        ),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private fun speedLabel(s: Float): String {
    val r = (s * 100).roundToInt() / 100f
    return (if (r % 1f == 0f) r.toInt().toString() else r.toString().trimEnd('0')) + "×"
}

// ---------------- Speed ----------------

@Composable
fun SpeedPanel(
    clip: Clip,
    onSpeed: (Float) -> Unit,
    onApplyAll: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    FloatingPanel {
        PanelHeader("Speed", speedLabel(clip.speed), onApplyAll)
        val presets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
        ChipRow {
            presets.forEach { s ->
                Pill(speedLabel(s), selected = clip.speed == s, selectedColor = Palette.Accent, onClick = { onSpeed(s) })
            }
        }
        AccentSlider(clip.speed, { onSpeed((it * 20).roundToInt() / 20f) }, Clip.MIN_SPEED..Clip.MAX_SPEED)
        Text(
            "Clip length ${formatTime(clip.durationUs, tenths = true)}",
            style = Type.Label,
            color = Palette.Muted,
        )
        PanelActions(onCancel, onDone)
    }
}

// ---------------- Volume ----------------

@Composable
fun VolumePanel(
    clip: Clip,
    onVolume: (Float) -> Unit,
    onApplyAll: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    FloatingPanel {
        PanelHeader("Volume", "${(clip.volume * 100).roundToInt()}%", onApplyAll)
        if (!clip.hasAudio) {
            Text("This clip has no sound.", style = Type.Body, color = Palette.Muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundButton(
                    Icons.Rounded.VolumeOff,
                    if (clip.volume == 0f) "Unmute" else "Mute",
                    { onVolume(if (clip.volume == 0f) 1f else 0f) },
                    style = if (clip.volume == 0f) RoundStyle.Accent else RoundStyle.Stone,
                )
                Column(Modifier.weight(1f)) {
                    AccentSlider(clip.volume, { onVolume((it * 20).roundToInt() / 20f) }, 0f..Clip.MAX_VOLUME)
                }
            }
            ChipRow {
                listOf(0f, 0.5f, 1f, 1.5f, 2f).forEach { v ->
                    Pill("${(v * 100).roundToInt()}%", selected = clip.volume == v, selectedColor = Palette.Accent, onClick = { onVolume(v) })
                }
            }
            if (clip.volume > 1f) {
                Text("Above 100% is applied on export.", style = Type.Label, color = Palette.Muted)
            }
        }
        PanelActions(onCancel, onDone)
    }
}

// ---------------- Transform ----------------

@Composable
fun TransformPanel(
    clip: Clip,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    onFill: (Boolean) -> Unit,
    onApplyAll: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    FloatingPanel {
        PanelHeader("Frame", "${clip.rotation}°", onApplyAll)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(Icons.Rounded.RotateRight, "Rotate 90°", onRotate, style = RoundStyle.White, size = 44.dp)
            RoundButton(
                Icons.Rounded.Flip, "Mirror", onFlip,
                style = if (clip.flipH) RoundStyle.Accent else RoundStyle.White, size = 44.dp,
            )
            Spacer(Modifier.weight(1f))
            Pill("Fit", selected = !clip.fill, selectedColor = Palette.Accent, onClick = { onFill(false) })
            Pill("Fill", selected = clip.fill, selectedColor = Palette.Accent, onClick = { onFill(true) })
        }
        PanelActions(onCancel, onDone)
    }
}

// ---------------- Canvas ----------------

@Composable
fun CanvasPanel(
    canvas: Canvas,
    onAspect: (Aspect) -> Unit,
    onBlur: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    FloatingPanel {
        PanelHeader("Canvas")
        ChipRow {
            Aspect.entries.forEach { a ->
                Pill(a.label, selected = canvas.aspect == a, selectedColor = Palette.Accent, onClick = { onAspect(a) })
            }
        }
        Text("Background", style = Type.Label, color = Palette.Muted)
        ChipRow {
            Pill("Black", selected = !canvas.blurBackground, selectedColor = Palette.Accent, onClick = { onBlur(false) })
            Pill("Blur", selected = canvas.blurBackground, selectedColor = Palette.Accent, onClick = { onBlur(true) })
        }
        PanelActions(onCancel, onDone)
    }
}

// ---------------- Photo duration ----------------

@Composable
fun DurationPanel(
    clip: Clip,
    onDuration: (Long) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val d = clip.trimEndUs - clip.trimStartUs
    FloatingPanel {
        PanelHeader("Photo length", formatTime(d, tenths = true))
        ChipRow {
            listOf(1, 2, 3, 5, 8, 10).forEach { s ->
                val us = s * 1_000_000L
                Pill("$s s", selected = d == us, selectedColor = Palette.Accent, onClick = { onDuration(us) })
            }
        }
        AccentSlider(
            d / 1_000_000f,
            { onDuration(((it * 10).roundToInt() / 10f * 1_000_000).toLong()) },
            (Timeline.MIN_CLIP_US / 1_000_000f)..(Clip.MAX_PHOTO_US / 1_000_000f),
        )
        PanelActions(onCancel, onDone)
    }
}
