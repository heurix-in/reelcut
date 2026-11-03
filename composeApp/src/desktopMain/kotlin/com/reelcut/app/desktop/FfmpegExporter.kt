package com.reelcut.app.desktop

import com.reelcut.app.data.AnimMath
import com.reelcut.app.data.CanvasMath
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Look
import com.reelcut.app.data.LookMath
import com.reelcut.app.data.OverlayAnim
import com.reelcut.app.data.Project
import com.reelcut.app.engine.ExportFailed
import androidx.compose.ui.graphics.toAwtImage
import com.reelcut.app.engine.OverlayLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext

/** A text or logo picture on disk with its time range and, if animated, how it moves (see [AnimMath]). */
class LayerFile(
    val file: File,
    val startUs: Long,
    val endUs: Long,
    val animIn: OverlayAnim = OverlayAnim.NONE,
    val animOut: OverlayAnim = OverlayAnim.NONE,
    val centreX: Float = 0.5f,
    val centreY: Float = 0.5f,
) {
    val isAnimated: Boolean get() = animIn != OverlayAnim.NONE || animOut != OverlayAnim.NONE
}

/**
 * Windows export: builds one ffmpeg command from the project (same framing rules as the
 * Android exporter and the preview) and runs the bundled ffmpeg program.
 */
class FfmpegExporter {

    val outputDir: File = File(System.getProperty("user.home"), "Videos" + File.separator + "Reelcut")

    private val encoder: String by lazy {
        val list = runCatching {
            val p = ProcessBuilder(Ffmpeg.executable, "-hide_banner", "-encoders").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrDefault("")
        listOf("libx264", "h264_mf", "libopenh264").firstOrNull { Regex("\\b$it\\b").containsMatchIn(list) } ?: "mpeg4"
    }

    suspend fun export(
        project: Project,
        settings: ExportSettings,
        layers: List<OverlayLayer>,
        onProgress: (Int) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            val clips = project.clips
            if (clips.isEmpty()) throw ExportFailed("There is nothing to export.")
            outputDir.mkdirs()
            val name = "Reelcut_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val out = File(outputDir, name)
            val log = File.createTempFile("reelcut-export", ".log")
            val layerDir = File(System.getProperty("java.io.tmpdir"), "reelcut-layers-" + System.nanoTime()).apply { mkdirs() }
            val layerFiles = layers.mapIndexed { i, layer ->
                val f = File(layerDir, "layer$i.png")
                ImageIO.write(layer.image.toAwtImage(), "png", f)
                LayerFile(f, layer.startUs, layer.endUs, layer.animIn, layer.animOut, layer.centreX, layer.centreY)
            }
            // One colour lookup file per distinct look. ffmpeg runs in this folder so the filter can use a plain file name.
            val lookFiles = clips.map { it.look }.filter { !it.isNeutral }.distinct()
                .mapIndexed { i, look -> look to "look$i.cube" }.toMap()
            lookFiles.forEach { (look, name) -> File(layerDir, name).writeText(LookMath.cube(look)) }
            val args = buildArgs(project, settings, out, layerFiles, lookFiles)
            val process = ProcessBuilder(listOf(Ffmpeg.executable) + args)
                .directory(layerDir)
                .redirectError(log)
                .start()
            val totalUs = clips.sumOf { it.durationUs }.coerceAtLeast(1L)
            var finished = false
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        coroutineContext.ensureActive()
                        if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                            val us = line.substringAfter('=').toLongOrNull() ?: continue
                            onProgress(((us * 100) / totalUs).toInt().coerceIn(0, 99))
                        }
                    }
                }
                val code = process.waitFor()
                if (code != 0) {
                    val tail = runCatching { log.readLines().takeLast(6).joinToString("\n") }.getOrDefault("")
                    throw ExportFailed(friendly(tail))
                }
                onProgress(100)
                finished = true
                out.absolutePath
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (!finished) {
                    withContext(NonCancellable) {
                        process.destroy()
                        runCatching { process.waitFor() }
                        out.delete()
                    }
                }
                log.delete()
                layerDir.deleteRecursively()
            }
        }

    private fun friendly(tail: String): String = when {
        tail.contains("No such file", true) || tail.contains("Permission denied", true) ->
            "A source file could not be opened. It may have been moved or deleted."
        tail.contains("Invalid data", true) -> "One of the files uses a format that can't be read."
        tail.isBlank() -> "Export failed."
        else -> "Export failed: " + tail.lines().last().take(160)
    }

    private fun num(v: Double) = String.format(Locale.US, "%.3f", v)

    internal fun buildArgs(
        project: Project,
        settings: ExportSettings,
        out: File,
        layers: List<LayerFile> = emptyList(),
        /** Colour lookup file name for each clip look (files sit in ffmpeg's working folder). */
        lookFiles: Map<Look, String> = emptyMap(),
    ): List<String> {
        val clips = project.clips
        val size = CanvasMath.outputSize(clips, project.canvas, settings.height)
        val w = size.width
        val h = size.height
        val fps = settings.fps
        val args = mutableListOf("-hide_banner", "-nostats", "-y", "-progress", "pipe:1")
        val graph = StringBuilder()
        var input = 0
        val padColor = "0x" + (project.canvas.backgroundArgb and 0xFFFFFFL).toString(16).padStart(6, '0')

        clips.forEachIndexed { i, c ->
            val clipSeconds = (c.trimEndUs - c.trimStartUs) / 1_000_000.0
            val timelineSeconds = c.durationUs / 1_000_000.0
            // Video input.
            val vIn = input++
            if (c.isPhoto) {
                args += listOf("-loop", "1", "-framerate", "$fps", "-t", num(clipSeconds), "-i", c.sourceUri)
            } else {
                args += listOf("-ss", num(c.trimStartUs / 1_000_000.0), "-t", num(clipSeconds), "-i", c.sourceUri)
            }
            val audible = !c.isPhoto && c.hasAudio && !project.muteOriginal && c.volume > 0f
            val aIn = if (audible) vIn else {
                val silent = input++
                args += listOf(
                    "-f", "lavfi", "-t", num(timelineSeconds),
                    "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
                )
                silent
            }

            // Video chain: speed, mirror, rotation, frame rate, framing.
            val v = StringBuilder("[$vIn:v]setpts=PTS-STARTPTS")
            if (!c.isPhoto && c.speed != 1f) v.append(",setpts=PTS/${num(c.speed.toDouble())}")
            if (c.flipH) v.append(",hflip")
            when (c.rotation) {
                90 -> v.append(",transpose=1")
                180 -> v.append(",hflip,vflip")
                270 -> v.append(",transpose=2")
            }
            // Colour look, before framing so the bars around the picture are not tinted.
            lookFiles[c.look]?.let { v.append(",format=rgb24,lut3d=file=$it") }
            v.append(",fps=$fps")
            val framed = "f$i"
            if (c.fill) {
                v.append(",scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,setsar=1[$framed];")
            } else if (project.canvas.blurBackground) {
                v.append(",split=2[bg$i][fg$i];")
                v.append("[bg$i]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,gblur=sigma=30[bgb$i];")
                v.append("[fg$i]scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2[fgs$i];")
                v.append("[bgb$i][fgs$i]overlay=(W-w)/2:(H-h)/2,setsar=1[$framed];")
            } else {
                v.append(",scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2")
                v.append(",pad=$w:$h:(ow-iw)/2:(oh-ih)/2:$padColor,setsar=1[$framed];")
            }
            v.append("[$framed]format=yuv420p,trim=end=${num(timelineSeconds)},setpts=PTS-STARTPTS[v$i];")
            graph.append(v)

            // Audio chain: speed, volume, exact length so clips stay in sync.
            val a = StringBuilder("[$aIn:a]asetpts=PTS-STARTPTS")
            if (audible) {
                if (c.speed != 1f) atempo(c.speed.toDouble()).forEach { a.append(",atempo=${num(it)}") }
                if (c.volume != 1f) a.append(",volume=${num(c.volume.toDouble())}")
            }
            a.append(",aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo")
            a.append(",apad,atrim=end=${num(timelineSeconds)},asetpts=PTS-STARTPTS[a$i];")
            graph.append(a)
        }
        clips.indices.forEach { graph.append("[v$it][a$it]") }
        graph.append("concat=n=${clips.size}:v=1:a=1[cat][outa]")

        // Text and logo layers: one transparent picture each, shown in its time range.
        var current = "cat"
        layers.forEachIndexed { k, layer ->
            val idx = input++
            val next = "ov$k"
            if (layer.isAnimated) {
                graph.append(';').append(animatedOverlay(layer, idx, current, next, w, h, fps, args))
            } else {
                args += listOf("-i", layer.file.absolutePath)
                graph.append(";[$current][$idx:v]overlay=0:0:enable='between(t,${num(layer.startUs / 1e6)},${num(layer.endUs / 1e6)})'[$next]")
            }
            current = next
        }
        graph.append(";[$current]format=yuv420p[outv]")

        // Music and voice-overs: trimmed, shaped (volume, fades), delayed to their start and mixed
        // under the video's own sound. The mix ends with the video's sound track.
        var audioOut = "outa"
        if (project.audio.isNotEmpty()) {
            val mixInputs = StringBuilder("[outa]")
            project.audio.forEachIndexed { k, a ->
                val idx = input++
                args += listOf("-ss", num(a.trimStartUs / 1e6), "-t", num(a.durationUs / 1e6), "-i", a.sourceUri)
                val seconds = a.durationUs / 1e6
                val chain = StringBuilder("[$idx:a]asetpts=PTS-STARTPTS")
                chain.append(",aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo")
                if (a.volume != 1f) chain.append(",volume=${num(a.volume.toDouble())}")
                if (a.fadeInUs > 0) chain.append(",afade=t=in:st=0:d=${num(a.fadeInUs / 1e6)}")
                if (a.fadeOutUs > 0) {
                    chain.append(",afade=t=out:st=${num((seconds - a.fadeOutUs / 1e6).coerceAtLeast(0.0))}:d=${num(a.fadeOutUs / 1e6)}")
                }
                val ms = a.startUs / 1000
                if (ms > 0) chain.append(",adelay=$ms|$ms")
                chain.append("[m$k]")
                graph.append(';').append(chain)
                mixInputs.append("[m$k]")
            }
            graph.append(';').append(mixInputs)
            graph.append("amix=inputs=${project.audio.size + 1}:duration=first:normalize=0:dropout_transition=0")
            graph.append(",alimiter=limit=0.98:level=0[mixa]")
            audioOut = "mixa"
        }

        val bitrate = CanvasMath.bitrate(size, settings)
        args += listOf("-filter_complex", graph.toString(), "-map", "[outv]", "-map", "[$audioOut]")
        args += listOf("-c:v", encoder, "-b:v", "$bitrate")
        if (encoder == "libx264") {
            args += listOf("-preset", "veryfast", "-maxrate", "${bitrate * 3 / 2}", "-bufsize", "${bitrate * 2}", "-profile:v", "high")
        }
        args += listOf("-r", "$fps", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.absolutePath)
        return args
    }

    /**
     * An animated text or logo layer. The picture is played as a looping stream shifted to its start time;
     * the fade is ffmpeg's alpha fade, and the slide and pop are expressions of the time `t` that follow
     * [AnimMath] exactly: position = centre * (1 - scale) + shift, so a pop grows about the overlay's centre.
     */
    private fun animatedOverlay(l: LayerFile, idx: Int, current: String, next: String, w: Int, h: Int, fps: Int, args: MutableList<String>): String {
        val s = l.startUs / 1e6
        val e = l.endUs / 1e6
        val d = AnimMath.durationUs(l.startUs, l.endUs) / 1e6
        args += listOf("-loop", "1", "-framerate", "$fps", "-t", num(e - s), "-i", l.file.absolutePath)
        val p = "clip((t-${num(s)})/${num(d)},0,1)" // 0 to 1 while coming in
        val r = "clip((t-${num(e - d)})/${num(d)},0,1)" // 0 to 1 while going out
        val short = minOf(w, h)
        val pop = AnimMath.POP_FROM
        val sIn = if (l.animIn == OverlayAnim.POP) "(${num(pop.toDouble())}+${num(1.0 - pop)}*(1-pow(1-$p,3)))" else "1"
        val sOut = if (l.animOut == OverlayAnim.POP) "(1-${num(1.0 - pop)}*pow($r,3))" else "1"
        val scaleExpr = "($sIn*$sOut)"
        fun shift(vIn: Float, vOut: Float): String {
            val parts = mutableListOf<String>()
            if (l.animIn != OverlayAnim.NONE && vIn != 0f) parts += "(${num(-vIn * AnimMath.SLIDE_DISTANCE.toDouble() * short)}*pow(1-$p,3))"
            if (l.animOut != OverlayAnim.NONE && vOut != 0f) parts += "(${num(vOut * AnimMath.SLIDE_DISTANCE.toDouble() * short)}*pow($r,3))"
            return if (parts.isEmpty()) "0" else parts.joinToString("+")
        }
        val (vxIn, vyIn) = AnimMath.direction(l.animIn)
        val (vxOut, vyOut) = AnimMath.direction(l.animOut)
        val x = "${num(l.centreX * w.toDouble())}*(1-$scaleExpr)+${shift(vxIn, vxOut)}"
        val y = "${num(l.centreY * h.toDouble())}*(1-$scaleExpr)+${shift(vyIn, vyOut)}"

        val chain = StringBuilder("[$idx:v]format=rgba,setpts=PTS+${num(s)}/TB")
        if (l.animIn != OverlayAnim.NONE) chain.append(",fade=t=in:st=${num(s)}:d=${num(d)}:alpha=1")
        if (l.animOut != OverlayAnim.NONE) chain.append(",fade=t=out:st=${num(e - d)}:d=${num(d)}:alpha=1")
        if (l.animIn == OverlayAnim.POP || l.animOut == OverlayAnim.POP) {
            chain.append(",scale=w='iw*$scaleExpr':h='ih*$scaleExpr':eval=frame")
        }
        chain.append("[anim$idx];")
        chain.append("[$current][anim$idx]overlay=x='$x':y='$y':eof_action=pass:enable='between(t,${num(s)},${num(e)})'[$next]")
        return chain.toString()
    }

    /** atempo takes 0.5–2.0 per step, so 0.25× and 4× are chained. */
    private fun atempo(speed: Double): List<Double> {
        val out = mutableListOf<Double>()
        var s = speed
        while (s > 2.0) { out += 2.0; s /= 2.0 }
        while (s < 0.5) { out += 0.5; s /= 0.5 }
        out += s
        return out
    }
}
