package com.reelcut.app.desktop

import androidx.compose.ui.graphics.ImageBitmap
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ClipKind
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Project
import com.reelcut.app.engine.ExportItem
import com.reelcut.app.engine.MediaEngine
import com.reelcut.app.engine.PreviewPlayer
import com.reelcut.app.engine.Thumbnails
import com.reelcut.app.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/** Windows: FFmpeg for import, preview and export; videos are saved in Videos\Reelcut. */
class DesktopMediaEngine : MediaEngine {

    override val thumbnails: Thumbnails = DesktopThumbnails()
    private val exporter = FfmpegExporter()

    override suspend fun importClips(refs: List<String>): List<Clip> = withContext(Dispatchers.IO) {
        refs.mapNotNull { path ->
            val file = File(path)
            if (!file.canRead()) return@mapNotNull null
            if (Ffmpeg.isImage(path)) {
                val (w, h) = Ffmpeg.photoSize(path) ?: return@mapNotNull null
                Clip(
                    id = newId(),
                    sourceUri = file.absolutePath,
                    sourceDurationUs = Clip.MAX_PHOTO_US,
                    width = w,
                    height = h,
                    hasAudio = false,
                    trimStartUs = 0,
                    trimEndUs = Clip.DEFAULT_PHOTO_US,
                    kind = ClipKind.PHOTO,
                    mimeType = "image/" + file.extension.lowercase(),
                )
            } else {
                val info = Ffmpeg.probeVideo(path) ?: return@mapNotNull null
                if (info.durationUs < 100_000L) return@mapNotNull null
                Clip(
                    id = newId(),
                    sourceUri = file.absolutePath,
                    sourceDurationUs = info.durationUs,
                    width = info.width,
                    height = info.height,
                    hasAudio = info.hasAudio,
                    trimStartUs = 0,
                    trimEndUs = info.durationUs,
                    kind = ClipKind.VIDEO,
                    mimeType = "video/" + file.extension.lowercase(),
                )
            }
        }
    }

    override fun isReadable(ref: String): Boolean = File(ref).canRead()

    override fun newPreview(): PreviewPlayer = DesktopPreviewPlayer()

    override suspend fun export(project: Project, settings: ExportSettings, onProgress: (Int) -> Unit): String =
        exporter.export(project, settings, onProgress)

    override val exportFolderLabel: String = "Videos\\Reelcut"
    override val shareLabel: String = "Show in folder"

    override suspend fun listExports(): List<ExportItem> = withContext(Dispatchers.IO) {
        exporter.outputDir.listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                val dur = Ffmpeg.probeVideo(f.absolutePath)?.durationUs ?: 0L
                ExportItem(f.absolutePath, f.name, dur / 1000)
            }
            ?: emptyList()
    }

    override suspend fun exportThumbnail(item: ExportItem): ImageBitmap? = withContext(Dispatchers.IO) {
        thumbnails.get(item.ref, 1_000_000L, 360)
    }

    override fun openVideo(ref: String) {
        runCatching { Desktop.getDesktop().open(File(ref)) }
    }

    override fun shareVideo(ref: String) {
        val file = File(ref)
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        runCatching {
            if (isWindows) {
                @Suppress("DEPRECATION")
                Runtime.getRuntime().exec("explorer.exe /select,\"${file.absolutePath}\"")
            } else {
                Desktop.getDesktop().open(file.parentFile)
            }
        }.onFailure { runCatching { Desktop.getDesktop().open(file.parentFile) } }
    }
}
