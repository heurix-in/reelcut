package com.reelcut.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.reelcut.app.data.Clip
import com.reelcut.app.data.Look
import com.reelcut.app.data.LookPreset
import com.reelcut.app.data.LookPresets
import com.reelcut.app.ui.components.Pill
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Type
import kotlin.math.roundToInt

/** Filter tool: preset looks as chips, and four sliders to adjust the picture. */
@Composable
fun FilterPanel(
    clip: Clip,
    onPreset: (LookPreset?) -> Unit,
    onLook: (Look) -> Unit,
    onApplyAll: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val look = clip.look
    FloatingPanel {
        PanelHeader("Filter", LookPresets.byId(clip.filterId)?.label, onApplyAll)
        ChipRow {
            Pill("None", selected = look.isNeutral, selectedColor = Palette.Accent, onClick = { onPreset(null) })
            LookPresets.all.forEach { p ->
                Pill(p.label, selected = clip.filterId == p.id, selectedColor = Palette.Accent, onClick = { onPreset(p) })
            }
        }
        LookSlider("Brightness", look.brightness) { onLook(look.copy(brightness = it)) }
        LookSlider("Contrast", look.contrast) { onLook(look.copy(contrast = it)) }
        LookSlider("Saturation", look.saturation) { onLook(look.copy(saturation = it)) }
        LookSlider("Warmth", look.warmth) { onLook(look.copy(warmth = it)) }
        PanelActions(onCancel, onDone)
    }
}

@Composable
private fun LookSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, style = Type.Label, color = Palette.Muted)
            Spacer(Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}", style = Type.Label, color = Palette.Accent)
        }
        AccentSlider(value, { onChange(snapToZero(it)) }, -1f..1f)
    }
}

/** Dragging past the middle should be able to land exactly on "no change". */
private fun snapToZero(v: Float): Float = if (kotlin.math.abs(v) < 0.03f) 0f else v
