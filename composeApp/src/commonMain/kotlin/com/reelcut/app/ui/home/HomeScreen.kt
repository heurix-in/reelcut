package com.reelcut.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.reelcut.app.MediaFilter
import com.reelcut.app.data.Project
import com.reelcut.app.data.ProjectRepository
import com.reelcut.app.engine.ExportItem
import com.reelcut.app.engine.MediaEngine
import com.reelcut.app.engine.Services
import com.reelcut.app.formatDate
import com.reelcut.app.nowMillis
import com.reelcut.app.rememberMediaPicker
import com.reelcut.app.ui.components.AccentButton
import com.reelcut.app.ui.components.ConfirmDialog
import com.reelcut.app.ui.components.Pill
import com.reelcut.app.ui.components.RenameDialog
import com.reelcut.app.ui.components.RoundButton
import com.reelcut.app.ui.components.RoundStyle
import com.reelcut.app.ui.components.formatTime
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Shape
import com.reelcut.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(services: Services, onOpen: (String) -> Unit) {
    val repo = services.repository
    val engine = services.engine
    val projects by repo.projects.collectAsState()
    val unreadable by repo.unreadable.collectAsState()
    var unreadableDismissed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { repo.refresh() }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            message = null
        }
    }

    val pick = rememberMediaPicker { refs ->
        if (refs.isEmpty()) return@rememberMediaPicker
        importing = true
        scope.launch {
            val clips = engine.importClips(refs)
            if (clips.isEmpty()) {
                importing = false
                message = "Those files could not be opened."
                return@launch
            }
            val now = nowMillis()
            val project = Project(
                id = ProjectRepository.newId(),
                name = "Video " + formatDate(now, "d MMM"),
                createdAt = now,
                updatedAt = now,
                clips = clips,
            )
            repo.save(project)
            importing = false
            onOpen(project.id)
        }
    }
    val newVideo = { pick(MediaFilter.All) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.InkDeep)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Rounded.Add, "New video", newVideo, style = RoundStyle.Accent, size = 48.dp, iconSize = 24.dp)
            }
            Spacer(Modifier.height(20.dp))
            Text("Create", style = Type.Headline)
            Text("and make", style = Type.Headline)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Last works", selected = tab == 0, onClick = { tab = 0 })
                Pill("Exports", selected = tab == 1, onClick = { tab = 1 })
            }
            Spacer(Modifier.height(16.dp))
            if (tab == 0 && unreadable.isNotEmpty() && !unreadableDismissed) {
                val n = unreadable.size
                Pill(
                    text = if (n == 1) "1 project couldn't be opened. Its file was left untouched. Tap to hide."
                    else "$n projects couldn't be opened. Their files were left untouched. Tap to hide.",
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    onClick = { unreadableDismissed = true },
                )
            }
            if (tab == 0) {
                ProjectList(services, projects, onOpen, onNew = newVideo)
            } else {
                ExportsGrid(engine)
            }
        }

        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Pill(message ?: "", selected = true, selectedColor = Palette.Accent)
        }

        if (importing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Palette.Accent)
            }
        }
    }
}

@Composable
private fun ProjectList(
    services: Services,
    projects: List<Project>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    val repo = services.repository
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }

    if (projects.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No projects yet", style = Type.Title, color = Palette.Muted)
            Spacer(Modifier.height(20.dp))
            AccentButton("New video", onNew)
        }
    } else {
        // One column on a phone, more on a wide window.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(projects, key = { it.id }) { p ->
                ProjectCard(
                    project = p,
                    services = services,
                    onOpen = { onOpen(p.id) },
                    onRename = { renaming = p },
                    onDuplicate = { scope.launch { repo.duplicate(p) } },
                    onDelete = { deleting = p },
                )
            }
        }
    }

    renaming?.let { p ->
        RenameDialog(p.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch { repo.save(p.copy(name = name, updatedAt = nowMillis())) }
        }
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete project?",
            message = "\"${p.name}\" will be removed. Your original videos and exports are kept.",
            confirm = "Delete",
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch { repo.delete(p.id) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    services: Services,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbs = services.engine.thumbnails
    var menu by remember { mutableStateOf(false) }
    val first = project.clips.firstOrNull()
    val cover by produceState<ImageBitmap?>(null, first?.sourceUri, first?.trimStartUs) {
        value = first?.let { thumbs.get(it.sourceUri, it.trimStartUs, 360) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(Shape.Card)
            .background(Palette.InkRaised)
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
    ) {
        cover?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000)))
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill(formatTime(project.durationUs))
            Pill(formatDate(project.updatedAt, "dd.MM.yyyy"))
        }
        Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            Box {
                RoundButton(Icons.Rounded.MoreHoriz, "More", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename", style = Type.Body) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate", style = Type.Body) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete", style = Type.Body, color = Palette.Danger) }, onClick = { menu = false; onDelete() })
                }
            }
            RoundButton(Icons.Rounded.Edit, "Edit", onOpen, style = RoundStyle.Accent)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(project.name, style = Type.Title, maxLines = 1)
            Text(
                "${project.clips.size} clip" + if (project.clips.size == 1) "" else "s",
                style = Type.Label,
                color = Palette.Muted,
            )
        }
    }
}

@Composable
private fun ExportsGrid(engine: MediaEngine) {
    val items by produceState<List<ExportItem>?>(null) { value = engine.listExports() }
    val list = items
    when {
        list == null -> Unit
        list.isEmpty() -> Text(
            "Exported videos will show here. They are saved in ${engine.exportFolderLabel}.",
            style = Type.Body,
            color = Palette.Muted,
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(list, key = { it.ref }) { item -> ExportTile(engine, item) }
        }
    }
}

@Composable
private fun ExportTile(engine: MediaEngine, item: ExportItem) {
    val thumb by produceState<ImageBitmap?>(null, item.ref) { value = engine.exportThumbnail(item) }
    Box(
        Modifier
            .widthIn(max = 260.dp)
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(Shape.Thumb)
            .background(Palette.InkRaised)
            .clickable { engine.openVideo(item.ref) }
    ) {
        thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Pill(formatTime(item.durationMs * 1000), modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
        Box(
            Modifier
                .align(Alignment.Center)
                .clip(Shape.Pill)
                .background(Palette.Glass)
                .padding(10.dp)
        ) {
            Icon(Icons.Rounded.PlayArrow, "Play", tint = Palette.White)
        }
    }
}
