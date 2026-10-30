package com.reelcut.app.ui.editor

import android.app.Application
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.reelcut.app.editor.EditorViewModel
import com.reelcut.app.editor.ExportState
import com.reelcut.app.editor.Mode
import com.reelcut.app.ui.components.AccentButton
import com.reelcut.app.ui.components.ConfirmDialog
import com.reelcut.app.ui.components.Pill
import com.reelcut.app.ui.components.RenameDialog
import com.reelcut.app.ui.components.RoundButton
import com.reelcut.app.ui.components.RoundStyle
import com.reelcut.app.ui.components.formatTime
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Type
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: EditorViewModel = viewModel(key = projectId) { EditorViewModel(app, projectId) }

    val project by vm.project.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val playheadUs by vm.playheadUs.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val exportState by vm.export.collectAsStateWithLifecycle()
    val trimRange by vm.trimRange.collectAsStateWithLifecycle()

    var dpPerSecond by rememberSaveable { mutableFloatStateOf(56f) }
    var renaming by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var insertAt by remember { mutableIntStateOf(-1) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1400)
            hint = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            vm.addClips(uris, at = insertAt.takeIf { it >= 0 }) { added ->
                hint = if (added == 0) "Those videos could not be opened" else if (added == 1) "Clip added" else "$added clips added"
            }
        }
        insertAt = -1
    }
    val pickVideos = { at: Int ->
        insertAt = at
        addMenu = false
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    when (mode) {
        Mode.Trim -> BackHandler { vm.finishTrim(apply = false) }
        Mode.Reorder -> BackHandler { vm.finishReorder() }
        Mode.Edit -> Unit
    }

    if (loadFailed) {
        Column(
            Modifier.fillMaxSize().background(Palette.Ink).statusBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("This project could not be opened.", style = Type.Title)
            AccentButton("Back", onBack)
        }
        return
    }
    val p = project
    if (p == null) {
        Box(Modifier.fillMaxSize().background(Palette.Ink), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.Accent)
        }
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ---- Preview, full bleed ----
        VideoSurface(
            vm.preview.player,
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    when (mode) {
                        Mode.Edit -> vm.togglePlay()
                        Mode.Trim -> vm.toggleTrimPlay()
                        Mode.Reorder -> Unit
                    }
                }
        )
        if (!isPlaying && p.clips.isNotEmpty() && mode == Mode.Edit) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Palette.Glass),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Palette.White, modifier = Modifier.size(32.dp))
            }
        }
        if (p.clips.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No clips yet", style = Type.Title)
                Spacer(Modifier.height(12.dp))
                AccentButton("Add videos", { pickVideos(0) })
            }
        }
        // Soft scrims so floating controls stay readable on bright video.
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
        )

        // ---- Top bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, style = RoundStyle.Ink)
            Spacer(Modifier.weight(1f))
            Pill(
                p.name,
                onClick = { renaming = true },
                leading = Icons.Rounded.Edit,
                modifier = Modifier.widthIn(max = 170.dp),
            )
            Spacer(Modifier.width(6.dp))
            RoundButton(Icons.Rounded.Add, "Add videos", { pickVideos(p.clips.size) }, style = RoundStyle.White, size = 30.dp, iconSize = 18.dp)
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.AutoMirrored.Rounded.Undo, "Undo", vm::undo, enabled = canUndo && mode == Mode.Edit, size = 36.dp, iconSize = 18.dp)
            RoundButton(Icons.AutoMirrored.Rounded.Redo, "Redo", vm::redo, enabled = canRedo && mode == Mode.Edit, size = 36.dp, iconSize = 18.dp)
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (p.clips.isNotEmpty() && mode == Mode.Edit) Palette.White else Palette.Stone)
                    .clickable(enabled = p.clips.isNotEmpty() && mode == Mode.Edit) {
                        vm.pause()
                        vm.resetExport()
                        showExport = true
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("Export", style = Type.Label, color = Palette.Ink)
            }
        }

        // ---- Hint ----
        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp),
        ) {
            Pill(hint ?: "", selected = true, selectedColor = Palette.Accent)
        }

        // ---- Tool rail ----
        if (mode == Mode.Edit && p.clips.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RailButton(Icons.Rounded.SettingsEthernet, "Trim") { vm.startTrim() }
                RailButton(Icons.Rounded.ContentCut, "Split", enabled = vm.canSplit()) {
                    vm.split()
                    hint = "Split at ${formatTime(playheadUs, tenths = true)}"
                }
                RailButton(Icons.Rounded.ContentCopy, "Duplicate") {
                    vm.duplicateTarget()
                    hint = "Clip duplicated"
                }
                RailButton(Icons.Rounded.SwapHoriz, "Reorder", enabled = p.clips.size > 1) { vm.startReorder() }
                RailButton(Icons.Rounded.DeleteOutline, "Delete") {
                    vm.deleteTarget()
                    hint = "Clip deleted · tap undo to restore"
                }
            }
        }

        // ---- Bottom ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (mode) {
                Mode.Edit -> if (p.clips.isNotEmpty()) {
                    // Add menu: a yellow blob that grows out of the "+".
                    Box(Modifier.fillMaxWidth().padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) {
                        AnimatedVisibility(
                            visible = addMenu,
                            enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)) + fadeIn(),
                            exit = scaleOut(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)) + fadeOut(),
                        ) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Palette.Accent)
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BlobButton(Icons.Rounded.Image, "Photo") { hint = "Photos arrive in phase 2"; addMenu = false }
                                BlobButton(Icons.Rounded.Videocam, "Video") { pickVideos(p.clips.size) }
                                BlobButton(Icons.Rounded.AutoAwesome, "Effects") { hint = "Effects arrive in phase 5"; addMenu = false }
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(Palette.GlassStrong)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundButton(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            vm::togglePlay,
                            style = RoundStyle.Ink,
                        )
                        TimelineStrip(
                            clips = p.clips,
                            missing = missing,
                            playheadUs = playheadUs,
                            selectedId = selectedId,
                            thumbs = vm.thumbnails,
                            dpPerSecond = dpPerSecond,
                            onZoom = { dpPerSecond = it },
                            onScrubStart = vm::pause,
                            onScrub = vm::seek,
                            onSelect = vm::select,
                            onInsertAt = { pickVideos(it) },
                            modifier = Modifier.weight(1f),
                        )
                        RoundButton(
                            Icons.Rounded.Add,
                            if (addMenu) "Close add menu" else "Add",
                            { addMenu = !addMenu },
                            style = RoundStyle.Accent,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TimeChips(
                            clips = p.clips,
                            playheadUs = playheadUs,
                            onJump = { vm.pause(); vm.seek(it, exact = true) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "${formatTime(playheadUs, tenths = true)} / ${formatTime(p.durationUs, tenths = true)}",
                        style = Type.Label,
                        color = Palette.Muted,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }

                Mode.Trim -> vm.trimClip()?.let { clip ->
                    TrimPanel(
                        clip = clip,
                        range = trimRange,
                        isPlaying = isPlaying,
                        thumbs = vm.thumbnails,
                        positionUs = vm::trimPositionUs,
                        onChange = { s, e, movingStart, exact -> vm.setTrim(s, e, movingStart, exact) },
                        onPlay = vm::toggleTrimPlay,
                        onCancel = { vm.finishTrim(apply = false) },
                        onDone = { vm.finishTrim(apply = true) },
                    )
                }

                Mode.Reorder -> ReorderPanel(
                    clips = p.clips,
                    thumbs = vm.thumbnails,
                    onMove = vm::move,
                    onDone = vm::finishReorder,
                )
            }
        }
    }

    // ---- Dialogs and sheets ----
    if (renaming) {
        RenameDialog(p.name, onDismiss = { renaming = false }) {
            renaming = false
            vm.rename(it)
        }
    }
    if (missing.isNotEmpty()) {
        ConfirmDialog(
            title = "Some videos are missing",
            message = "${missing.size} clip(s) can't be found. They may have been moved or deleted. Remove them to keep editing.",
            confirm = "Remove",
            dismiss = "Go back",
            onDismiss = onBack,
            onConfirm = vm::removeMissing,
        )
    }
    if (showExport) {
        ExportSheet(
            clips = p.clips,
            initial = p.export,
            state = exportState,
            onExport = vm::startExport,
            onCancelExport = vm::cancelExport,
            onReset = vm::resetExport,
            onDismiss = {
                showExport = false
                vm.resetExport()
            },
        )
    }
    if (exportState is ExportState.Done && !showExport) {
        LaunchedEffect(exportState) {
            Toast.makeText(context, "Saved to Movies/Reelcut", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    RoundButton(icon, label, onClick, style = RoundStyle.Glass, size = 42.dp, enabled = enabled)
}

@Composable
private fun BlobButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFFD9D466))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Palette.Ink, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.BLACK)
                setKeepContentOnPlayerReset(true)
                this.player = player
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}
