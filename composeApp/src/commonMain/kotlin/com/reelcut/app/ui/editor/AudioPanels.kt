package com.reelcut.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.reelcut.app.data.AudioClip
import com.reelcut.app.ui.components.GhostButton
import com.reelcut.app.ui.components.Pill
import com.reelcut.app.ui.components.RoundButton
import com.reelcut.app.ui.components.RoundStyle
import com.reelcut.app.ui.components.formatOneDecimal
import com.reelcut.app.ui.components.formatTime
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Type
import kotlin.math.roundToInt

private enum class AudioTab(val label: String) { Volume("Volume"), Fade("Fade"), Trim("Trim"), Start("Start") }

/**
 * Audio tool. With nothing selected it adds music, records a voice-over and switches the video's
 * own sound; with a sound selected it edits that sound's volume, fades, cut and start.
 */
@Composable
fun AudioPanel(
    audio: List<AudioClip>,
    selected: AudioClip?,
    muteOriginal: Boolean,
    playheadUs: Long,
    totalUs: Long,
    recordingUs: Long?,
    onAddMusic: () -> Unit,
    onRecord: () -> Unit,
    onSelect: (String?) -> Unit,
    onChange: ((AudioClip) -> AudioClip) -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: () -> Unit,
    onToggleMuteOriginal: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    FloatingPanel {
        when {
            recordingUs != null -> RecordingView(recordingUs, onStop = onRecord, onDiscard = onCancel)
            selected == null -> {
                Text("Audio", style = Type.Title)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigTile(Icons.Rounded.MusicNote, "Add music", onAddMusic, Modifier.weight(1f))
                    BigTile(Icons.Rounded.Mic, "Record voice", onRecord, Modifier.weight(1f))
                }
                ChipRow {
                    Pill(
                        if (muteOriginal) "Video sound off" else "Video sound on",
                        selected = !muteOriginal,
                        selectedColor = Palette.Accent,
                        onClick = onToggleMuteOriginal,
                        leading = if (muteOriginal) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    )
                }
                if (audio.isNotEmpty()) {
                    Text("Your sounds", style = Type.Label, color = Palette.Muted)
                    ChipRow {
                        audio.forEach { a ->
                            Pill(
                                (if (a.isVoice) "Voice · " else "Music · ") + a.name,
                                onClick = { onSelect(a.id) },
                                modifier = Modifier,
                            )
                        }
                    }
                } else {
                    Text(
                        "Music starts at the playhead. For a voice-over the video plays silently while you talk; headphones keep the room quiet.",
                        style = Type.Label,
                        color = Palette.Muted,
                    )
                }
                PanelActions(onCancel, onDone)
            }
            else -> EditView(selected, playheadUs, totalUs, onSelect, onChange, onSeek, onDelete, onCancel, onDone)
        }
    }
}

@Composable
private fun RecordingView(elapsedUs: Long, onStop: () -> Unit, onDiscard: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.Danger))
        Text("Recording", style = Type.Title)
        Spacer(Modifier.weight(1f))
        Text(formatTime(elapsedUs, tenths = true), style = Type.Title, color = Palette.Accent)
    }
    Text(
        "Talk along with the video. It stops by itself when the video ends.",
        style = Type.Label,
        color = Palette.Muted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("Discard", onDiscard, Modifier.weight(1f))
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .background(Palette.Danger)
                .clickable(onClick = onStop)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Stop, null, tint = Palette.Ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(6.dp))
            Text("Stop", style = Type.Title, color = Palette.Ink)
        }
    }
}

@Composable
private fun EditView(
    clip: AudioClip,
    playheadUs: Long,
    totalUs: Long,
    onSelect: (String?) -> Unit,
    onChange: ((AudioClip) -> AudioClip) -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(AudioTab.Volume) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill("All sounds", onClick = { onSelect(null) })
        Text(clip.name.ifBlank { if (clip.isVoice) "Voice" else "Music" }, style = Type.Title, maxLines = 1, modifier = Modifier.weight(1f))
        RoundButton(Icons.Rounded.DeleteOutline, "Delete sound", onDelete, style = RoundStyle.Stone, size = 36.dp, iconSize = 18.dp)
    }
    ChipRow {
        AudioTab.entries.forEach { t ->
            Pill(t.label, selected = tab == t, selectedColor = Palette.Accent, onClick = { tab = t })
        }
    }
    when (tab) {
        AudioTab.Volume -> {
            AudioSliderRow("Volume", "${(clip.volume * 100).roundToInt()}%") {
                AccentSlider(clip.volume, { v -> onChange { it.copy(volume = (v * 20).roundToInt() / 20f) } }, 0f..AudioClip.MAX_VOLUME)
            }
            Text("Above 100% you hear the boost in the exported video, not in the preview.", style = Type.Label, color = Palette.Muted)
        }
        AudioTab.Fade -> {
            val maxFade = minOf(AudioClip.MAX_FADE_US, clip.durationUs).toFloat().coerceAtLeast(1f)
            AudioSliderRow("Fade in", seconds(clip.fadeInUs)) {
                AccentSlider(clip.fadeInUs.toFloat(), { v -> onChange { it.copy(fadeInUs = v.toLong()) } }, 0f..maxFade)
            }
            AudioSliderRow("Fade out", seconds(clip.fadeOutUs)) {
                AccentSlider(clip.fadeOutUs.toFloat(), { v -> onChange { it.copy(fadeOutUs = v.toLong()) } }, 0f..maxFade)
            }
        }
        AudioTab.Trim -> {
            AudioSliderRow("Cut from start", formatTime(clip.trimStartUs, tenths = true)) {
                AccentSlider(
                    clip.trimStartUs.toFloat(),
                    { v -> onChange { it.copy(trimStartUs = v.toLong()) } },
                    0f..(clip.trimEndUs - AudioClip.MIN_US).coerceAtLeast(1L).toFloat(),
                )
            }
            AudioSliderRow("Cut from end", formatTime(clip.sourceDurationUs - clip.trimEndUs, tenths = true)) {
                AccentSlider(
                    clip.trimEndUs.toFloat(),
                    { v -> onChange { it.copy(trimEndUs = v.toLong()) } },
                    (clip.trimStartUs + AudioClip.MIN_US).coerceAtMost(clip.sourceDurationUs).toFloat()..clip.sourceDurationUs.toFloat(),
                )
            }
            Text("Plays ${formatTime(clip.durationUs, tenths = true)} of ${formatTime(clip.sourceDurationUs, tenths = true)}", style = Type.Label, color = Palette.White)
        }
        AudioTab.Start -> {
            AudioSliderRow("Playhead", formatTime(playheadUs, tenths = true)) {
                AccentSlider(playheadUs.toFloat(), { v -> onSeek(v.toLong()) }, 0f..totalUs.coerceAtLeast(1L).toFloat())
            }
            Text(
                "Starts ${formatTime(clip.startUs, tenths = true)} → ends ${formatTime(clip.endUs, tenths = true)}",
                style = Type.Label,
                color = Palette.White,
            )
            ChipRow {
                Pill("Start here", onClick = { onChange { it.copy(startUs = playheadUs) } })
                Pill("At the beginning", onClick = { onChange { it.copy(startUs = 0L) } })
            }
            Text("Move the playhead, then tap Start here.", style = Type.Label, color = Palette.Muted)
        }
    }
    PanelActions(onCancel, onDone)
}

private fun seconds(us: Long): String = if (us <= 0L) "Off" else formatOneDecimal(us / 1_000_000.0) + " s"

@Composable
private fun AudioSliderRow(label: String, value: String, slider: @Composable () -> Unit) {
    Column {
        Row {
            Text(label, style = Type.Label, color = Palette.Muted)
            Spacer(Modifier.weight(1f))
            Text(value, style = Type.Label, color = Palette.Accent)
        }
        slider()
    }
}

@Composable
private fun BigTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.InkRaised)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.Ink, modifier = Modifier.size(22.dp))
        }
        Text(label, style = Type.Label)
    }
}
