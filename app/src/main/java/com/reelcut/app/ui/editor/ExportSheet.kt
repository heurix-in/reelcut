package com.reelcut.app.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.reelcut.app.data.Canvas
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Quality
import com.reelcut.app.data.Timeline
import com.reelcut.app.editor.ExportState
import com.reelcut.app.media.CompositionBuilder
import com.reelcut.app.ui.components.AccentButton
import com.reelcut.app.ui.components.GhostButton
import com.reelcut.app.ui.components.Pill
import com.reelcut.app.ui.home.openVideo
import com.reelcut.app.ui.home.shareVideo
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Shape
import com.reelcut.app.ui.theme.Type

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    clips: List<Clip>,
    canvas: Canvas,
    initial: ExportSettings,
    state: ExportState,
    onExport: (ExportSettings) -> Unit,
    onCancelExport: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = state is ExportState.Running
    val runningState = rememberUpdatedState(running)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !runningState.value },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!running) onDismiss() },
        sheetState = sheetState,
        containerColor = Palette.Ink,
        shape = Shape.Sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                ExportState.Idle -> SettingsContent(clips, canvas, initial, onExport)
                is ExportState.Running -> RunningContent(state.percent, onCancelExport)
                is ExportState.Done -> DoneContent(state, onDismiss)
                is ExportState.Failed -> FailedContent(state.message, onRetry = onReset, onClose = onDismiss)
            }
        }
    }
}

@Composable
private fun SettingsContent(clips: List<Clip>, canvas: Canvas, initial: ExportSettings, onExport: (ExportSettings) -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(initial) }
    val needsPermission = Build.VERSION.SDK_INT < 29 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onExport(settings)
    }

    Text("Export", style = Type.Title.copy(fontSize = 22.sp))
    Option("Resolution") {
        Pill("720p", selected = settings.height == 720, selectedColor = Palette.Accent, onClick = { settings = settings.copy(height = 720) })
        Pill("1080p", selected = settings.height == 1080, selectedColor = Palette.Accent, onClick = { settings = settings.copy(height = 1080) })
    }
    Option("Frame rate") {
        Pill("30 fps", selected = settings.fps == 30, selectedColor = Palette.Accent, onClick = { settings = settings.copy(fps = 30) })
        Pill("60 fps", selected = settings.fps == 60, selectedColor = Palette.Accent, onClick = { settings = settings.copy(fps = 60) })
    }
    Option("Quality") {
        Quality.entries.forEach { q ->
            Pill(
                q.name.lowercase().replaceFirstChar { it.uppercase() },
                selected = settings.quality == q,
                selectedColor = Palette.Accent,
                onClick = { settings = settings.copy(quality = q) },
            )
        }
    }
    val size = CompositionBuilder.outputSize(clips, canvas, settings)
    val seconds = Timeline.totalUs(clips) / 1_000_000.0
    val mb = (CompositionBuilder.bitrate(size, settings) + 128_000) * seconds / 8 / 1_000_000
    Text(
        "${size.width} × ${size.height} · about ${"%.0f".format(mb.coerceAtLeast(1.0))} MB",
        style = Type.Label,
        color = Palette.Muted,
    )
    AccentButton(
        "Export video",
        onClick = {
            if (needsPermission) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else onExport(settings)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = clips.isNotEmpty(),
    )
}

@Composable
private fun Option(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Type.Label, color = Palette.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun RunningContent(percent: Int, onCancel: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.size(132.dp),
            color = Palette.Accent,
            trackColor = Palette.InkRaised,
            strokeWidth = 8.dp,
        )
        Text("$percent%", style = Type.Title.copy(fontSize = 26.sp))
    }
    Text(
        "Exporting… keep Reelcut open.",
        style = Type.Body,
        color = Palette.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    GhostButton("Cancel", onCancel, Modifier.fillMaxWidth())
}

@Composable
private fun DoneContent(state: ExportState.Done, onClose: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Palette.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, null, tint = Palette.Ink, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Saved to Movies/Reelcut", style = Type.Title)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("Play", { openVideo(context, state.uri) }, Modifier.weight(1f))
        AccentButton("Share", { shareVideo(context, state.uri) }, Modifier.weight(1f))
    }
    GhostButton("Back to editing", onClose, Modifier.fillMaxWidth())
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Danger)
        Text("Export didn't finish", style = Type.Title)
    }
    Text(message, style = Type.Body, color = Palette.Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("Close", onClose, Modifier.weight(1f))
        AccentButton("Try again", onRetry, Modifier.weight(1f))
    }
}
