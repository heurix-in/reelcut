package com.reelcut.app.media

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.reelcut.app.data.Clip
import com.reelcut.app.data.blankClip
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Project
import com.reelcut.app.engine.ExportItem
import com.reelcut.app.engine.MediaEngine
import com.reelcut.app.engine.OverlayLayer
import com.reelcut.app.engine.PreviewPlayer
import com.reelcut.app.engine.Thumbnails
import com.reelcut.app.engine.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Android: Media3 for preview and export, MediaStore for saving to the gallery. */
class AndroidMediaEngine(private val context: Context) : MediaEngine {

    override val thumbnails: Thumbnails = ThumbnailCache(context)

    private val exporter = Exporter(context)

    override suspend fun importClips(refs: List<String>) = MediaProbe.clipsFrom(context, refs)

    override suspend fun importAudio(refs: List<String>) = MediaProbe.audioFrom(context, refs)

    override suspend fun createBlankClip(argb: Long, width: Int, height: Int): Clip? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(context.filesDir, "blank/blank_" + argb.toString(16) + ".png")
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.eraseColor(argb.toInt())
                file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            blankClip(Uri.fromFile(file).toString(), width, height)
        }.getOrNull()
    }

    override fun newRecorder(): VoiceRecorder = AndroidVoiceRecorder(context, File(context.filesDir, "voice"))

    override fun isReadable(ref: String): Boolean = MediaProbe.isReadable(context, ref)

    override fun newPreview(): PreviewPlayer = AndroidPreviewPlayer(context)

    override suspend fun export(
        project: Project,
        settings: ExportSettings,
        layers: List<OverlayLayer>,
        onProgress: (Int) -> Unit,
    ): String {
        val androidLayers = layers.map { CompositionBuilder.Layer(it.image.asAndroidBitmap(), it.startUs, it.endUs) }
        return exporter.export(
            project.clips, project.canvas, project.muteOriginal, settings, androidLayers, project.audio, onProgress,
        ).toString()
    }

    override suspend fun loadImage(ref: String, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        MediaProbe.decodePhoto(context, Uri.parse(ref), maxSide)?.asImageBitmap()
    }

    override val exportFolderLabel = "Movies/Reelcut"
    override val shareLabel = "Share"

    override suspend fun listExports(): List<ExportItem> = withContext(Dispatchers.IO) {
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
                    out += ExportItem(uri.toString(), c.getString(nameCol) ?: "", c.getLong(durCol))
                }
            }
        }
        out
    }

    override suspend fun exportThumbnail(item: ExportItem): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(Uri.parse(item.ref), Size(360, 480), null).asImageBitmap()
            } else null
        }.getOrNull()
    }

    override fun openVideo(ref: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(ref), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "No app found to play the video.", Toast.LENGTH_SHORT).show() }
    }

    override fun shareVideo(ref: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(ref))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }
}
