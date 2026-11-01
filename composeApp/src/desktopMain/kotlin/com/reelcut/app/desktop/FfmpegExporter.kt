package com.reelcut.app.desktop

import com.reelcut.app.data.CanvasMath
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Project
import com.reelcut.app.engine.ExportFailed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

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

    suspend fun export(project: Project, settings: ExportSettings, onProgress: (Int) -> Unit): String =
        withContext(Dispatchers.IO) {
            val clips = project.clips
            if (clips.isEmpty()) throw ExportFailed("There is nothing to export.")
            outputDir.mkdirs()
            val name = "Reelcut_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val out = File(outputDir, name)
            val log = File.createTempFile("reelcut-export", ".log")
            val args = buildArgs(project, settings, out)
            val process = ProcessBuilder(listOf(Ffmpeg.executable) + args)
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

    internal fun buildArgs(project: Project, settings: ExportSettings, out: File): List<String> {
        val clips = project.clips
        val size = CanvasMath.outputSize(clips, project.canvas, settings.height)
        val w = size.width
        val h = size.height
        val fps = settings.fps
        val args = mutableListOf("-hide_banner", "-nostats", "-y", "-progress", "pipe:1")
        val graph = StringBuilder()
        var input = 0

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
                v.append(",pad=$w:$h:(ow-iw)/2:(oh-ih)/2:black,setsar=1[$framed];")
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
        graph.append("concat=n=${clips.size}:v=1:a=1[outv][outa]")

        val bitrate = CanvasMath.bitrate(size, settings)
        args += listOf("-filter_complex", graph.toString(), "-map", "[outv]", "-map", "[outa]")
        args += listOf("-c:v", encoder, "-b:v", "$bitrate")
        if (encoder == "libx264") {
            args += listOf("-preset", "veryfast", "-maxrate", "${bitrate * 3 / 2}", "-bufsize", "${bitrate * 2}", "-profile:v", "high")
        }
        args += listOf("-r", "$fps", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out.absolutePath)
        return args
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
