package com.reelcut.app.ui.home

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelcut.app.ReelcutApp
import com.reelcut.app.data.Project
import com.reelcut.app.data.ProjectRepository
import com.reelcut.app.media.MediaProbe
import com.reelcut.app.media.ThumbnailCache
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as ReelcutApp).container
    val repo = container.repository
    val projects by repo.projects.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { repo.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val clips = MediaProbe.clipsFrom(context, uris)
            if (clips.isEmpty()) {
                importing = false
                Toast.makeText(context, "Those videos could not be opened.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val now = System.currentTimeMillis()
            val project = Project(
                id = ProjectRepository.newId(),
                name = "Video " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(now)),
                createdAt = now,
                updatedAt = now,
                clips = clips,
            )
            repo.save(project)
            importing = false
            onOpen(project.id)
        }
    }
    val newVideo = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

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
            if (tab == 0) {
                ProjectList(projects, container.thumbnails, onOpen, onNew = newVideo)
            } else {
                ExportsGrid()
            }
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
    projects: List<Project>,
    thumbs: ThumbnailCache,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    val repo = (LocalContext.current.applicationContext as ReelcutApp).container.repository
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
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(projects, key = { it.id }) { p ->
                ProjectCard(
                    project = p,
                    thumbs = thumbs,
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
            scope.launch { repo.save(p.copy(name = name, updatedAt = System.currentTimeMillis())) }
        }
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete project?",
            message = "\"${p.name}\" will be removed. Your original videos and exports stay in your gallery.",
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
    thumbs: ThumbnailCache,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
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
            Pill(SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(project.updatedAt)))
        }
        RoundButton(
            Icons.Rounded.Edit, "Edit", onOpen,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            style = RoundStyle.Accent,
        )
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(project.name, style = Type.Title, maxLines = 1)
            Text(
                "${project.clips.size} clip" + if (project.clips.size == 1) "" else "s",
                style = Type.Label,
                color = Palette.Muted,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename", style = Type.Body) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Duplicate", style = Type.Body) }, onClick = { menu = false; onDuplicate() })
            DropdownMenuItem(text = { Text("Delete", style = Type.Body, color = Palette.Danger) }, onClick = { menu = false; onDelete() })
        }
    }
}

private data class ExportItem(val uri: Uri, val name: String, val durationMs: Long)

@Composable
private fun ExportsGrid() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < 29) {
        Text("Your exports are saved in Movies/Reelcut in your gallery.", style = Type.Body, color = Palette.Muted)
        return
    }
    val items by produceState<List<ExportItem>?>(null) { value = loadExports(context) }
    val list = items
    when {
        list == null -> Unit
        list.isEmpty() -> Text(
            "Exported videos will show here. They are saved in Movies/Reelcut.",
            style = Type.Body,
            color = Palette.Muted,
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(list, key = { it.uri.toString() }) { item -> ExportTile(item) }
        }
    }
}

@Composable
private fun ExportTile(item: ExportItem) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(item.uri, Size(360, 480), null).asImageBitmap()
                } else null
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(Shape.Thumb)
            .background(Palette.InkRaised)
            .clickable { openVideo(context, item.uri) }
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

private suspend fun loadExports(context: Context): List<ExportItem> = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < 29) return@withContext emptyList()
    val out = mutableListOf<ExportItem>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
    )
    runCatching {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Movies/Reelcut%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
                out += ExportItem(uri, c.getString(nameCol) ?: "", c.getLong(durCol))
            }
        }
    }
    out
}

fun openVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No app found to play the video.", Toast.LENGTH_SHORT).show() }
}

fun shareVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share video")) }
}
