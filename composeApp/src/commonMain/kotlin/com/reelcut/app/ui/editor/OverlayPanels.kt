package com.reelcut.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.reelcut.app.data.Overlay
import com.reelcut.app.data.TextAlignment
import com.reelcut.app.data.TextFont
import com.reelcut.app.ui.components.ColorPicker
import com.reelcut.app.ui.components.Pill
import com.reelcut.app.ui.components.RoundButton
import com.reelcut.app.ui.components.RoundStyle
import com.reelcut.app.ui.components.formatTime
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Type
import kotlin.math.roundToInt

private enum class TextTab(val label: String) {
    Edit("Text"), Font("Font"), Colour("Colour"), Outline("Outline"), Label("Label"), Shadow("Shadow"), Time("Time")
}

/** Text tool: words, font and size, colours, outline, label box, shadow and timing. */
@Composable
fun TextPanel(
    overlay: Overlay,
    playheadUs: Long,
    totalUs: Long,
    isNew: Boolean,
    onChange: ((Overlay) -> Overlay) -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(TextTab.Edit) }
    FloatingPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Text", style = Type.Title)
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Rounded.DeleteOutline, "Delete text", onDelete, style = RoundStyle.Stone, size = 36.dp, iconSize = 18.dp)
        }
        ChipRow {
            TextTab.entries.forEach { t ->
                Pill(t.label, selected = tab == t, selectedColor = Palette.Accent, onClick = { tab = t })
            }
        }
        when (tab) {
            TextTab.Edit -> {
                val focus = remember { FocusRequester() }
                OutlinedTextField(
                    value = overlay.text,
                    onValueChange = { v -> onChange { it.copy(text = v.take(300)) } },
                    placeholder = { Text("Type your text", style = Type.Body, color = Palette.Muted) },
                    textStyle = Type.Body,
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Palette.Accent,
                        unfocusedBorderColor = Palette.Stone,
                        cursorColor = Palette.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (isNew) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Align", style = Type.Label, color = Palette.Muted)
                    Spacer(Modifier.width(4.dp))
                    listOf(
                        TextAlignment.LEFT to Icons.AutoMirrored.Rounded.FormatAlignLeft,
                        TextAlignment.CENTER to Icons.Rounded.FormatAlignCenter,
                        TextAlignment.RIGHT to Icons.AutoMirrored.Rounded.FormatAlignRight,
                    ).forEach { (a, icon) ->
                        RoundButton(
                            icon, a.name.lowercase(),
                            { onChange { it.copy(align = a) } },
                            style = if (overlay.align == a) RoundStyle.Accent else RoundStyle.Stone,
                            size = 36.dp, iconSize = 18.dp,
                        )
                    }
                }
                Text("Drag the text on the video to move it. Pinch, or drag the yellow dot, to resize and turn it.",
                    style = Type.Label, color = Palette.Muted)
            }
            TextTab.Font -> {
                ChipRow {
                    TextFont.entries.forEach { f ->
                        Pill(f.label, selected = overlay.font == f, selectedColor = Palette.Accent, onClick = { onChange { it.copy(font = f) } })
                    }
                }
                SliderRow("Size", "${(overlay.scale * 100).roundToInt()}%") {
                    AccentSlider(overlay.scale, { v -> onChange { it.copy(scale = v) } }, Overlay.MIN_SCALE..4f)
                }
                SliderRow("Opacity", "${(overlay.opacity * 100).roundToInt()}%") {
                    AccentSlider(overlay.opacity, { v -> onChange { it.copy(opacity = v) } }, 0.1f..1f)
                }
            }
            TextTab.Colour -> ColorPicker(overlay.colorArgb, { c -> onChange { it.copy(colorArgb = c) } })
            TextTab.Outline -> {
                ColorPicker(overlay.outlineArgb, { c -> onChange { it.copy(outlineArgb = c) } }, allowNone = true)
                if (overlay.outlineArgb != 0L) {
                    SliderRow("Width", "${(overlay.outlineWidth * 100).roundToInt()}") {
                        AccentSlider(overlay.outlineWidth, { v -> onChange { it.copy(outlineWidth = v) } }, 0.02f..0.25f)
                    }
                }
            }
            TextTab.Label -> ColorPicker(overlay.labelArgb, { c -> onChange { it.copy(labelArgb = c) } }, allowNone = true)
            TextTab.Shadow -> ChipRow {
                Pill("No shadow", selected = !overlay.shadow, selectedColor = Palette.Accent, onClick = { onChange { it.copy(shadow = false) } })
                Pill("Soft shadow", selected = overlay.shadow, selectedColor = Palette.Accent, onClick = { onChange { it.copy(shadow = true) } })
            }
            TextTab.Time -> TimingControls(overlay, playheadUs, totalUs, onChange, onSeek)
        }
        PanelActions(onCancel, onDone)
    }
}

/** Logo tool: size, opacity, quick positions and timing. */
@Composable
fun LogoPanel(
    overlay: Overlay,
    playheadUs: Long,
    totalUs: Long,
    onChange: ((Overlay) -> Overlay) -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    FloatingPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Logo", style = Type.Title)
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Rounded.DeleteOutline, "Delete logo", onDelete, style = RoundStyle.Stone, size = 36.dp, iconSize = 18.dp)
        }
        SliderRow("Size", "${(overlay.scale * 100).roundToInt()}%") {
            AccentSlider(overlay.scale, { v -> onChange { it.copy(scale = v) } }, Overlay.MIN_SCALE..3f)
        }
        SliderRow("Opacity", "${(overlay.opacity * 100).roundToInt()}%") {
            AccentSlider(overlay.opacity, { v -> onChange { it.copy(opacity = v) } }, 0.1f..1f)
        }
        Text("Position", style = Type.Label, color = Palette.Muted)
        ChipRow {
            listOf(
                "Top left" to (0.18f to 0.1f),
                "Top right" to (0.82f to 0.1f),
                "Centre" to (0.5f to 0.5f),
                "Bottom left" to (0.18f to 0.9f),
                "Bottom right" to (0.82f to 0.9f),
            ).forEach { (label, pos) ->
                Pill(label, onClick = { onChange { it.copy(x = pos.first, y = pos.second, rotation = 0f) } })
            }
        }
        TimingControls(overlay, playheadUs, totalUs, onChange, onSeek)
        PanelActions(onCancel, onDone)
    }
}

@Composable
private fun SliderRow(label: String, value: String, slider: @Composable () -> Unit) {
    Column {
        Row {
            Text(label, style = Type.Label, color = Palette.Muted)
            Spacer(Modifier.weight(1f))
            Text(value, style = Type.Label, color = Palette.Accent)
        }
        slider()
    }
}

/** Start/end of an overlay, set from the playhead. */
@Composable
private fun TimingControls(
    overlay: Overlay,
    playheadUs: Long,
    totalUs: Long,
    onChange: ((Overlay) -> Overlay) -> Unit,
    onSeek: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SliderRow("Playhead", formatTime(playheadUs, tenths = true)) {
            AccentSlider(
                playheadUs.toFloat(),
                { v -> onSeek(v.toLong()) },
                0f..totalUs.coerceAtLeast(1L).toFloat(),
            )
        }
        Text(
            "Shows ${formatTime(overlay.startUs, tenths = true)} → ${formatTime(overlay.endUs, tenths = true)}" +
                "  (${formatTime(overlay.endUs - overlay.startUs, tenths = true)})",
            style = Type.Label,
            color = Palette.White,
        )
        ChipRow {
            Pill("Start here", onClick = {
                onChange {
                    val s = playheadUs.coerceAtMost((it.endUs - Overlay.MIN_US).coerceAtLeast(0L))
                    it.copy(startUs = s)
                }
            })
            Pill("End here", onClick = {
                onChange { it.copy(endUs = playheadUs.coerceAtLeast(it.startUs + Overlay.MIN_US)) }
            })
            Pill("Whole video", onClick = { onChange { it.copy(startUs = 0L, endUs = totalUs.coerceAtLeast(Overlay.MIN_US)) } })
        }
        Text("Move the playhead, then tap Start here or End here.", style = Type.Label, color = Palette.Muted)
    }
}
