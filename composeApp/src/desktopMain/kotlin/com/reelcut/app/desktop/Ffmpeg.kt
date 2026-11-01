package com.reelcut.app.desktop

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/** Small helpers around FFmpeg (through JavaCV) shared by import, thumbnails and preview. */
object Ffmpeg {

    @Volatile private var ready = false

    fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            // Use the GPL build of FFmpeg (it includes the x264 encoder).
            if (System.getProperty("org.bytedeco.javacpp.platform.extension") == null) {
                System.setProperty("org.bytedeco.javacpp.platform.extension", "-gpl")
            }
            runCatching { avutil.av_log_set_level(avutil.AV_LOG_ERROR) }
            runCatching { FFmpegLogCallback.set() }
            ready = true
        }
    }

    /** Path of the bundled ffmpeg program (extracted on first use). */
    val executable: String by lazy {
        init()
        Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java)
    }

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    val videoExtensions = setOf("mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp", "wmv", "mts", "m2ts", "ts", "flv")
    val mediaExtensions = imageExtensions + videoExtensions
    val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "wma", "aiff", "aif", "amr")

    fun isImage(path: String): Boolean = File(path).extension.lowercase() in imageExtensions

    data class VideoInfo(
        val durationUs: Long,
        /** Size as displayed (rotation applied). */
        val width: Int,
        val height: Int,
        val hasAudio: Boolean,
        /** Clockwise rotation the frames need to stand upright. */
        val rotation: Int,
    )

    fun probeVideo(path: String): VideoInfo? = runCatching {
        init()
        val g = FFmpegFrameGrabber(path)
        try {
            g.start()
            if (!g.hasVideo() || g.imageWidth <= 0) return@runCatching null
            val rot = rotationOf(g)
            val w = g.imageWidth
            val h = g.imageHeight
            VideoInfo(
                durationUs = g.lengthInTime,
                width = if (rot % 180 == 0) w else h,
                height = if (rot % 180 == 0) h else w,
                hasAudio = g.hasAudio() && g.audioChannels > 0,
                rotation = rot,
            )
        } finally {
            runCatching { g.close() }
        }
    }.getOrNull()

    /** Length in microseconds of a sound file, or null if it has no playable sound. */
    fun probeAudioUs(path: String): Long? = runCatching {
        init()
        val g = FFmpegFrameGrabber(path)
        try {
            g.start()
            if (!g.hasAudio() || g.audioChannels <= 0) return@runCatching null
            g.lengthInTime.takeIf { it > 0 }
        } finally {
            runCatching { g.close() }
        }
    }.getOrNull()

    /** Clockwise degrees (0, 90, 180, 270) from the file's display matrix or "rotate" tag. */
    fun rotationOf(g: FFmpegFrameGrabber): Int {
        val fromMatrix = runCatching { g.displayRotation }.getOrDefault(0.0)
        val degrees = if (fromMatrix != 0.0 && !fromMatrix.isNaN()) {
            // The display matrix gives counter-clockwise degrees.
            (-fromMatrix).roundToInt()
        } else {
            g.getVideoMetadata("rotate")?.toIntOrNull() ?: 0
        }
        val norm = ((degrees % 360) + 360) % 360
        return ((norm + 45) / 90 * 90) % 360
    }

    fun photoSize(path: String): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(File(path)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@runCatching null
            val reader = readers.next()
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull() ?: decodePhoto(path, 4096)?.let { it.width to it.height }

    /** Decodes a photo with its long side at most [maxSide]. Falls back to FFmpeg for formats Java can't read. */
    fun decodePhoto(path: String, maxSide: Int): BufferedImage? {
        val img = runCatching { ImageIO.read(File(path)) }.getOrNull() ?: runCatching {
            init()
            val g = FFmpegFrameGrabber(path)
            try {
                g.start()
                g.grabImage()?.let { Java2DFrameConverter().convert(it)?.let(::copyOf) }
            } finally {
                runCatching { g.close() }
            }
        }.getOrNull() ?: return null
        return scaleToFit(img, maxSide)
    }

    fun scaleToFit(img: BufferedImage, maxSide: Int): BufferedImage {
        val long = max(img.width, img.height)
        if (long <= maxSide) return img
        val s = maxSide.toDouble() / long
        return scaled(img, max(1, (img.width * s).roundToInt()), max(1, (img.height * s).roundToInt()))
    }

    fun scaled(img: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    fun copyOf(img: BufferedImage): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return out
    }

    /** Rotates clockwise by 0/90/180/270 degrees. */
    fun rotated(img: BufferedImage, degrees: Int): BufferedImage {
        if (degrees % 360 == 0) return img
        val quarter = degrees % 180 != 0
        val w = if (quarter) img.height else img.width
        val h = if (quarter) img.width else img.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        val t = AffineTransform()
        t.translate(w / 2.0, h / 2.0)
        t.rotate(Math.toRadians(degrees.toDouble()))
        t.translate(-img.width / 2.0, -img.height / 2.0)
        g.drawImage(img, t, null)
        g.dispose()
        return out
    }

    /**
     * Picks the decode size for a video whose coded size is [w] x [h], with its long side at most
     * [maxSide]; both sides even.
     */
    fun decodeSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
        val long = max(w, h)
        if (long <= maxSide) return w to h
        val s = maxSide.toDouble() / long
        fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)
        return even(w * s) to even(h * s)
    }

    /** Converts a decoded video frame into an upright image, copying the pixels. */
    fun frameToImage(frame: Frame, converter: Java2DFrameConverter, rotation: Int): BufferedImage? {
        val img = converter.convert(frame) ?: return null
        return if (rotation == 0) copyOf(img) else rotated(img, rotation)
    }
}
