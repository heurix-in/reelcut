package com.reelcut.app.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.reelcut.app.engine.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.util.Collections

/** Timeline and card frames on Windows, decoded with FFmpeg one at a time on a background thread. */
class DesktopThumbnails : Thumbnails {

    private val memory: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 600
        }
    )

    private class Open(val grabber: FFmpegFrameGrabber, val rotation: Int, var height: Int)

    /** A few files kept open so neighbouring frames are quick. */
    private val open = object : LinkedHashMap<String, Open>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Open>?): Boolean {
            if (size > 3) {
                eldest?.value?.let { runCatching { it.grabber.close() } }
                return true
            }
            return false
        }
    }
    private val converter = Java2DFrameConverter()
    private val lock = Mutex()

    override fun quantize(timeUs: Long): Long = (timeUs / STEP_US) * STEP_US

    private fun timeKey(ref: String, timeUs: Long) = if (Ffmpeg.isImage(ref)) 0L else quantize(timeUs)

    private fun key(ref: String, t: Long, h: Int) = "$ref|$t|$h"

    override fun peek(ref: String, timeUs: Long, heightPx: Int): ImageBitmap? =
        memory[key(ref, timeKey(ref, timeUs), heightPx)]

    override suspend fun get(ref: String, timeUs: Long, heightPx: Int): ImageBitmap? {
        val t = timeKey(ref, timeUs)
        val k = key(ref, t, heightPx)
        memory[k]?.let { return it }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                memory[k] ?: decode(ref, t, heightPx)?.also { memory[k] = it }
            }
        }
    }

    private fun decode(ref: String, timeUs: Long, heightPx: Int): ImageBitmap? = try {
        if (Ffmpeg.isImage(ref)) {
            Ffmpeg.decodePhoto(ref, heightPx * 2)?.let { img ->
                val w = (img.width.toLong() * heightPx / img.height).toInt().coerceAtLeast(1)
                Ffmpeg.scaled(img, w, heightPx).toComposeImageBitmap()
            }
        } else {
            decodeVideo(ref, timeUs, heightPx)
        }
    } catch (e: Throwable) {
        open.remove(ref)?.let { runCatching { it.grabber.close() } }
        null
    }

    private fun decodeVideo(ref: String, timeUs: Long, heightPx: Int): ImageBitmap? {
        Ffmpeg.init()
        val o = open.getOrPut(ref) {
            val g = FFmpegFrameGrabber(ref)
            g.start()
            Open(g, Ffmpeg.rotationOf(g), 0)
        }
        val g = o.grabber
        // Decode straight to the small size (before rotation, so swap for sideways videos).
        val codedW = g.imageWidth.coerceAtLeast(1)
        val codedH = g.imageHeight.coerceAtLeast(1)
        val shortSideTarget = heightPx
        val (dw, dh) = if (o.rotation % 180 == 0) {
            ((codedW.toLong() * shortSideTarget / codedH).toInt() to shortSideTarget)
        } else {
            (shortSideTarget to (codedH.toLong() * shortSideTarget / codedW).toInt())
        }
        g.imageWidth = dw.coerceAtLeast(2)
        g.imageHeight = dh.coerceAtLeast(2)
        val target = timeUs.coerceIn(0L, (g.lengthInTime - 1).coerceAtLeast(0L))
        g.setTimestamp(target, true)
        val frame = g.grabImage() ?: return null
        val img = Ffmpeg.frameToImage(frame, converter, o.rotation) ?: return null
        return img.toComposeImageBitmap()
    }

    companion object {
        const val STEP_US = 500_000L
    }
}
