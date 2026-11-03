package com.reelcut.app.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.CropRotate
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.rememberTextMeasurer
import com.reelcut.app.data.Overlay
import com.reelcut.app.data.OverlayKind
import com.reelcut.app.engine.MediaEngine
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reelcut.app.MediaFilter
import com.reelcut.app.PlatformBackHandler
import com.reelcut.app.data.Project
import com.reelcut.app.data.Timeline
import com.reelcut.app.editor.EditorViewModel
import com.reelcut.app.editor.ExportState
import com.reelcut.app.editor.Mode
import com.reelcut.app.engine.Services
import com.reelcut.app.rememberAudioPicker
import com.reelcut.app.rememberMediaPicker
import com.reelcut.app.rememberMicPermission
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

/**
 * InShot-style editor in the Gixlo colours: the video on its own at the top, a playback row,
 * a labelled toolbar that changes with the selection, and a ruler timeline at the bottom.
 * Tools open their panel in place of the toolbar and timeline.
 */
@Composable
fun EditorScreen(services: Services, projectId: String, onBack: () -> Unit) {
    val vm: EditorViewModel = viewModel(key = projectId) { EditorViewModel(services, projectId) }

    val project by vm.project.collectAsState()
    val loadFailed by vm.loadFailed.collectAsState()
    val saveFailed by vm.saveFailed.collectAsState()
    val missing by vm.missing.collectAsState()
    val playheadUs by vm.playheadUs.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val mode by vm.mode.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val exportState by vm.export.collectAsState()
    val trimRange by vm.trimRange.collectAsState()
    val selectedOverlayId by vm.selectedOverlayId.collectAsState()
    val selectedAudioId by vm.selectedAudioId.collectAsState()
    val recordingUs by vm.recordingUs.collectAsState()
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val currentIndex by vm.preview.currentIndex.collectAsState()

    var dpPerSecond by rememberSaveable { mutableFloatStateOf(56f) }
    var renaming by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var insertAt by remember { mutableIntStateOf(-1) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1600)
            hint = null
        }
    }
    LaunchedEffect(saveFailed) {
        if (saveFailed) hint = "Couldn't save your changes. Is the storage full?"
    }

    val pick = rememberMediaPicker { refs ->
        if (refs.isNotEmpty()) {
            vm.addClips(refs, at = insertAt.takeIf { it >= 0 }) { added ->
                hint = when (added) {
                    0 -> "Those files could not be opened"
                    1 -> "Clip added"
                    else -> "$added clips added"
                }
            }
        }
        insertAt = -1
    }
    val addMedia = { at: Int, filter: MediaFilter ->
        insertAt = at
        pick(filter)
    }

    val pickAudio = rememberAudioPicker { refs ->
        if (refs.isNotEmpty()) {
            vm.addAudio(refs) { added ->
                hint = when (added) {
                    0 -> "That sound could not be opened"
                    1 -> "Sound added at the playhead"
                    else -> "$added sounds added"
                }
            }
        }
    }
    val withMic = rememberMicPermission()
    val toggleRecording = {
        if (recordingUs != null) {
            vm.stopRecording { saved -> if (!saved) hint = "That recording was too short" }
        } else {
            withMic { if (!vm.startRecording()) hint = "The microphone could not be opened" }
        }
    }

    val pickLogo = rememberMediaPicker { refs ->
        if (refs.isNotEmpty()) vm.addLogo(refs) { hint = "That picture could not be opened" }
    }

    PlatformBackHandler(enabled = mode != Mode.Edit || selectedId != null) {
        when (mode) {
            Mode.Trim -> vm.finishTrim(apply = false)
            Mode.Reorder -> vm.finishReorder()
            Mode.Edit -> vm.deselect()
            else -> vm.closePanel(apply = false)
        }
    }

    if (loadFailed) {
        Column(
            Modifier.fillMaxSize().background(Palette.InkDeep).statusBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("This project could not be opened.", style = Type.Title)
            AccentButton("Back", onBack)
        }
        return
    }
    val p = project
    if (p == null) {
        Box(Modifier.fillMaxSize().background(Palette.InkDeep), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.Accent)
        }
        return
    }

    val logos = rememberLogos(p.overlays, services.engine)
    val selectedIndex = p.clips.indexOfFirst { it.id == selectedId }
    val selected = p.clips.getOrNull(selectedIndex)
    val soon = { name: String -> hint = "$name arrives in the next update" }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.InkDeep)
    ) {
        // ---- Top bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, style = RoundStyle.Ink)
            Spacer(Modifier.width(6.dp))
            Pill(
                p.name,
                onClick = { renaming = true },
                leading = Icons.Rounded.Edit,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            Spacer(Modifier.weight(1f))
            val canExport = p.clips.isNotEmpty() && mode == Mode.Edit
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (canExport) Palette.Accent else Palette.Stone)
                    .clickable(enabled = canExport) {
                        vm.pause()
                        vm.resetExport()
                        showExport = true
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text("Export", style = Type.Label, color = if (canExport) Palette.Ink else Palette.Muted)
            }
        }

        // ---- Preview ----
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val trimClip = if (mode == Mode.Trim) vm.trimClip() else null
            val shownClip = trimClip ?: p.clips.getOrNull(currentIndex)
            val shownSourceUs = shownClip?.let { c ->
                val pos = Timeline.locate(p.clips, playheadUs)
                if (pos != null && p.clips[pos.index].id == c.id) Timeline.sourceTimeUs(c, pos.offsetUs) else c.trimStartUs
            } ?: 0L
            CanvasPreview(
                player = vm.preview,
                clips = p.clips,
                canvas = p.canvas,
                current = shownClip,
                sourceTimeUs = shownSourceUs,
                trimMode = mode == Mode.Trim,
                thumbs = vm.thumbnails,
                overlays = OverlayState(
                    overlays = p.overlays,
                    timeUs = playheadUs,
                    selectedId = selectedOverlayId,
                    measurer = measurer,
                    logos = logos,
                    onTap = { o ->
                        when {
                            o != null && (mode == Mode.Edit || mode == Mode.Text || mode == Mode.Logo) -> vm.editOverlay(o)
                            mode == Mode.Trim -> vm.toggleTrimPlay()
                            else -> vm.togglePlay()
                        }
                    },
                    onChange = { id, t -> if (id == selectedOverlayId) vm.stageSelectedOverlay(t) },
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .background(Palette.InkDeep)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (mode == Mode.Trim) vm.toggleTrimPlay() else vm.togglePlay()
                    },
            )
            if (p.clips.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No clips yet", style = Type.Title)
                    Spacer(Modifier.height(12.dp))
                    AccentButton("Add videos or photos", { addMedia(0, MediaFilter.All) })
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = hint != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            ) {
                Pill(hint ?: "", selected = true, selectedColor = Palette.Accent)
            }
        }

        // ---- Playback row ----
        Box(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 8.dp),
        ) {
            Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RoundButton(Icons.AutoMirrored.Rounded.Undo, "Undo", vm::undo, enabled = canUndo && mode == Mode.Edit, size = 36.dp, iconSize = 18.dp)
                RoundButton(Icons.AutoMirrored.Rounded.Redo, "Redo", vm::redo, enabled = canRedo && mode == Mode.Edit, size = 36.dp, iconSize = 18.dp)
            }
            RoundButton(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                { if (mode == Mode.Trim) vm.toggleTrimPlay() else vm.togglePlay() },
                modifier = Modifier.align(Alignment.Center),
                style = RoundStyle.White,
                size = 44.dp,
                iconSize = 24.dp,
                enabled = p.clips.isNotEmpty(),
            )
            Text(
                "${formatTime(playheadUs, tenths = true)} / ${formatTime(p.durationUs, tenths = true)}",
                style = Type.Label,
                color = Palette.Muted,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            )
        }

        // ---- Bottom: toolbar + timeline, or the open tool's panel ----
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 6.dp),
        ) {
            when (mode) {
                Mode.Edit -> {
                    if (selected != null) {
                        ToolBar(
                            leading = {
                                RoundButton(Icons.Rounded.Check, "Done", vm::deselect, style = RoundStyle.Accent, size = 40.dp)
                            },
                            tools = clipTools(vm, p, selected.isPhoto) { hint = it },
                        )
                    } else {
                        ToolBar(
                            tools = listOf(
                                Tool(Icons.Rounded.AspectRatio, "Canvas", enabled = p.clips.isNotEmpty()) { vm.openPanel(Mode.Canvas) },
                                Tool(Icons.Rounded.TextFields, "Text", enabled = p.clips.isNotEmpty()) { vm.addText() },
                                Tool(Icons.Rounded.MusicNote, "Audio", enabled = p.clips.isNotEmpty()) { vm.openAudio() },
                                Tool(Icons.Rounded.Image, "Logo", enabled = p.clips.isNotEmpty()) { pickLogo(MediaFilter.Photo) },
                                Tool(Icons.Rounded.AutoAwesome, "Filter", soon = true) { soon("Filter") },
                                Tool(Icons.Rounded.SwapHoriz, "Reorder", enabled = p.clips.size > 1) { vm.startReorder() },
                                Tool(Icons.Rounded.AddPhotoAlternate, "Add") { addMedia(vm.insertIndexAtPlayhead(), MediaFilter.All) },
                                Tool(Icons.Rounded.CropSquare, "Blank") {
                                    vm.addBlank { ok ->
                                        hint = if (ok) "Black frame added · tap Length to change it" else "Could not make a blank frame"
                                    }
                                },
                            ),
                        )
                    }
                    if (p.clips.isNotEmpty()) {
                        TimelineArea(
                            project = p,
                            missing = missing,
                            playheadUs = playheadUs,
                            selectedId = selectedId,
                            vm = vm,
                            dpPerSecond = dpPerSecond,
                            onZoom = { dpPerSecond = it },
                            onAdd = { addMedia(vm.insertIndexAtPlayhead(), MediaFilter.All) },
                            onInsertAt = { addMedia(it, MediaFilter.All) },
                            onMuteToggled = { muted -> hint = if (muted) "Clip sound muted" else "Clip sound on" },
                            selectedAudioId = selectedAudioId,
                        )
                    }
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

                Mode.Speed -> vm.panelClip()?.let { clip ->
                    SpeedPanel(
                        clip = clip,
                        onSpeed = vm::setSpeed,
                        onApplyAll = { vm.stageAll { c -> c.copy(speed = clip.speed) }; hint = "Speed applied to all clips" },
                        onCancel = { vm.closePanel(apply = false) },
                        onDone = { vm.closePanel(apply = true) },
                    )
                }

                Mode.Volume -> vm.panelClip()?.let { clip ->
                    VolumePanel(
                        clip = clip,
                        onVolume = vm::setVolume,
                        onApplyAll = { vm.stageAll { c -> c.copy(volume = clip.volume) }; hint = "Volume applied to all clips" },
                        onCancel = { vm.closePanel(apply = false) },
                        onDone = { vm.closePanel(apply = true) },
                    )
                }

                Mode.Transform -> vm.panelClip()?.let { clip ->
                    TransformPanel(
                        clip = clip,
                        onRotate = vm::rotate,
                        onFlip = vm::flip,
                        onFill = vm::setFill,
                        onApplyAll = {
                            vm.stageAll { c -> c.copy(rotation = clip.rotation, flipH = clip.flipH, fill = clip.fill) }
                            hint = "Applied to all clips"
                        },
                        onCancel = { vm.closePanel(apply = false) },
                        onDone = { vm.closePanel(apply = true) },
                    )
                }

                Mode.Canvas -> CanvasPanel(
                    canvas = p.canvas,
                    onAspect = { a -> vm.stageCanvas { it.copy(aspect = a) } },
                    onBlur = { b -> vm.stageCanvas { it.copy(blurBackground = b) } },
                    onColor = { c -> vm.stageCanvas { it.copy(backgroundArgb = c, blurBackground = false) } },
                    onCancel = { vm.closePanel(apply = false) },
                    onDone = { vm.closePanel(apply = true) },
                )

                Mode.Text, Mode.Logo -> p.overlays.firstOrNull { it.id == selectedOverlayId }?.let { o ->
                    if (o.isText) {
                        TextPanel(
                            overlay = o,
                            playheadUs = playheadUs,
                            totalUs = p.durationUs,
                            isNew = vm.isNewOverlay,
                            onChange = vm::stageSelectedOverlay,
                            onSeek = { vm.seek(it, exact = true) },
                            onDelete = vm::deleteOverlay,
                            onCancel = { vm.closePanel(apply = false) },
                            onDone = { vm.closePanel(apply = true) },
                        )
                    } else {
                        LogoPanel(
                            overlay = o,
                            playheadUs = playheadUs,
                            totalUs = p.durationUs,
                            onChange = vm::stageSelectedOverlay,
                            onSeek = { vm.seek(it, exact = true) },
                            onDelete = vm::deleteOverlay,
                            onCancel = { vm.closePanel(apply = false) },
                            onDone = { vm.closePanel(apply = true) },
                        )
                    }
                }

                Mode.Audio -> AudioPanel(
                    audio = p.audio,
                    selected = p.audio.firstOrNull { it.id == selectedAudioId },
                    muteOriginal = p.muteOriginal,
                    playheadUs = playheadUs,
                    totalUs = p.durationUs,
                    recordingUs = recordingUs,
                    onAddMusic = { pickAudio() },
                    onRecord = toggleRecording,
                    onSelect = vm::selectAudio,
                    onChange = vm::stageSelectedAudio,
                    onSeek = { vm.seek(it, exact = true) },
                    onDelete = vm::deleteSelectedAudio,
                    onToggleMuteOriginal = vm::stageMuteOriginal,
                    onCancel = { vm.closePanel(apply = false) },
                    onDone = { vm.closePanel(apply = true) },
                )

                Mode.Duration -> vm.panelClip()?.let { clip ->
                    DurationPanel(
                        clip = clip,
                        onDuration = vm::setPhotoDuration,
                        onCancel = { vm.closePanel(apply = false) },
                        onDone = { vm.closePanel(apply = true) },
                    )
                }
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
            engine = services.engine,
            clips = p.clips,
            canvas = p.canvas,
            initial = p.export,
            state = exportState,
            onExport = { settings ->
                val renderer = OverlayRenderer(measurer, services.engine)
                vm.startExport(settings) { overlays, w, h -> renderer.render(overlays, w, h) }
            },
            onCancelExport = vm::cancelExport,
            onReset = vm::resetExport,
            onDismiss = {
                showExport = false
                vm.resetExport()
            },
        )
    }
}

/** Tools for the selected clip, InShot order. */
private fun clipTools(vm: EditorViewModel, p: Project, isPhoto: Boolean, hint: (String) -> Unit): List<Tool> = buildList {
    add(Tool(Icons.Rounded.ContentCut, "Split", enabled = vm.canSplit()) { vm.split(); hint("Split") })
    if (isPhoto) {
        add(Tool(Icons.Rounded.Timer, "Length") { vm.openPanel(Mode.Duration) })
    } else {
        add(Tool(Icons.Rounded.SettingsEthernet, "Trim") { vm.startTrim() })
        add(Tool(Icons.Rounded.Speed, "Speed") { vm.openPanel(Mode.Speed) })
        add(Tool(Icons.Rounded.VolumeUp, "Volume") { vm.openPanel(Mode.Volume) })
    }
    add(Tool(Icons.Rounded.CropRotate, "Rotate") { vm.openPanel(Mode.Transform) })
    add(Tool(Icons.Rounded.ContentCopy, "Duplicate") { vm.duplicateTarget(); hint("Clip duplicated") })
    add(Tool(Icons.Rounded.SwapHoriz, "Reorder", enabled = p.clips.size > 1) { vm.startReorder() })
    add(Tool(Icons.Rounded.DeleteOutline, "Delete") { vm.deleteTarget(); hint("Clip deleted · tap undo to restore") })
}

/** Timeline with the "+" and "Mute clip" controls floating at its left, InShot style. */
@Composable
private fun TimelineArea(
    project: Project,
    missing: Set<String>,
    playheadUs: Long,
    selectedId: String?,
    vm: EditorViewModel,
    dpPerSecond: Float,
    onZoom: (Float) -> Unit,
    onAdd: () -> Unit,
    onInsertAt: (Int) -> Unit,
    onMuteToggled: (Boolean) -> Unit,
    selectedAudioId: String?,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            TimelineStrip(
                clips = project.clips,
                missing = missing,
                playheadUs = playheadUs,
                selectedId = selectedId,
                thumbs = vm.thumbnails,
                dpPerSecond = dpPerSecond,
                onZoom = onZoom,
                onScrubStart = vm::pause,
                onScrub = vm::seek,
                onSelect = vm::select,
                overlays = project.overlays,
                selectedOverlayId = null,
                onSelectOverlay = vm::editOverlay,
                audio = project.audio,
                selectedAudioId = selectedAudioId,
                onSelectAudio = vm::editAudio,
                onInsertAt = onInsertAt,
                modifier = Modifier.fillMaxWidth(),
            )
            TimeRuler(
                playheadUs = playheadUs,
                dpPerSecond = dpPerSecond,
                totalUs = project.durationUs,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                Text(
                    formatTime(playheadUs, tenths = true),
                    style = Type.Label,
                    color = Palette.White,
                    modifier = Modifier.align(Alignment.Center),
                )
                Text(
                    formatTime(project.durationUs, tenths = true),
                    style = Type.Label,
                    color = Palette.Muted,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                )
            }
        }
        // Left controls over the past part of the strip, fading into it.
        Row(
            Modifier
                .height(60.dp)
                .background(
                    Brush.horizontalGradient(
                        0f to Palette.InkDeep,
                        0.8f to Palette.InkDeep,
                        1f to Palette.InkDeep.copy(alpha = 0f),
                    )
                )
                .padding(start = 10.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Palette.Accent)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, "Add videos or photos", tint = Palette.Ink, modifier = Modifier.size(26.dp))
            }
            val muted = project.muteOriginal
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        vm.toggleMuteOriginal()
                        onMuteToggled(!muted)
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    if (muted) "Unmute clips" else "Mute clips",
                    tint = if (muted) Palette.Accent else Palette.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    if (muted) "Muted" else "Mute clip",
                    style = Type.Small,
                    color = if (muted) Palette.Accent else Palette.Muted,
                )
            }
        }
    }
}

/** Logo pictures for the preview, loaded once per file. */
@Composable
private fun rememberLogos(overlays: List<Overlay>, engine: MediaEngine): Map<String, ImageBitmap> {
    val uris = overlays.filter { it.kind == OverlayKind.LOGO }.map { it.imageUri }.distinct()
    val state = produceState(emptyMap<String, ImageBitmap>(), uris) {
        val loaded = value.toMutableMap()
        uris.filterNot { it in loaded }.forEach { uri ->
            engine.loadImage(uri, 1024)?.let { loaded[uri] = it }
        }
        value = loaded.toMap()
    }
    return state.value
}
