package com.reelcut.app.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioKind
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ClipKind
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Project
import com.reelcut.app.engine.ExportItem
import com.reelcut.app.engine.MediaEngine
import com.reelcut.app.engine.OverlayLayer
import com.reelcut.app.engine.PreviewPlayer
import com.reelcut.app.engine.Thumbnails
import com.reelcut.app.engine.VoiceRecorder
import com.reelcut.app.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Windows: FFmpeg for import, preview and export; videos are saved in Videos\Reelcut. */
class DesktopMediaEngine(private val voiceDir: File) : MediaEngine {

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

    override suspend fun importAudio(refs: List<String>): List<AudioClip> = withContext(Dispatchers.IO) {
        refs.mapNotNull { path ->
            val file = File(path)
            if (!file.canRead()) return@mapNotNull null
            val dur = Ffmpeg.probeAudioUs(path) ?: return@mapNotNull null
            if (dur < AudioClip.MIN_US) return@mapNotNull null
            AudioClip(
                id = newId(),
                kind = AudioKind.MUSIC,
                name = file.nameWithoutExtension.ifBlank { "Audio" }.take(60),
                sourceUri = file.absolutePath,
                sourceDurationUs = dur,
                trimEndUs = dur,
            )
        }
    }

    override fun newRecorder(): VoiceRecorder = DesktopVoiceRecorder(voiceDir)

    override fun isReadable(ref: String): Boolean = File(ref).canRead()

    override fun newPreview(): PreviewPlayer = DesktopPreviewPlayer()

    override suspend fun export(
        project: Project,
        settings: ExportSettings,
        layers: List<OverlayLayer>,
        onProgress: (Int) -> Unit,
    ): String = exporter.export(project, settings, layers, onProgress)

    /** Keeps transparency (unlike thumbnails), for logos. */
    override suspend fun loadImage(ref: String, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val img = ImageIO.read(File(ref)) ?: return@runCatching null
            val long = maxOf(img.width, img.height)
            val scaled = if (long <= maxSide) img else {
                val s = maxSide.toDouble() / long
                val w = (img.width * s).toInt().coerceAtLeast(1)
                val h = (img.height * s).toInt().coerceAtLeast(1)
                val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val g = out.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, 0, 0, w, h, null)
                g.dispose()
                out
            }
            scaled.toComposeImageBitmap()
        }.getOrNull()
    }

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
