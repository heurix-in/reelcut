package com.reelcut.app.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioMath
import com.reelcut.app.data.Clip
import com.reelcut.app.data.Timeline
import com.reelcut.app.engine.PreviewPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt

/**
 * Windows preview. Frames are decoded with FFmpeg at preview size on a worker thread and shown as
 * images; sound is decoded on a second thread and played through Java Sound. Both follow one clock.
 *
 * The playlist is a list of segments: the edit's clips, or (in trim mode) one whole source file.
 */
class DesktopPreviewPlayer : PreviewPlayer {

    private data class Segment(
        val path: String,
        val sourceStartUs: Long,
        val sourceEndUs: Long,
        val speed: Float,
        val volume: Float,
        val hasAudio: Boolean,
        val isPhoto: Boolean,
        val timelineStartUs: Long,
    ) {
        val timelineDurationUs: Long get() = ((sourceEndUs - sourceStartUs) / speed).toLong()
        val timelineEndUs: Long get() = timelineStartUs + timelineDurationUs
    }

    private val frame = MutableStateFlow<ImageBitmap?>(null)
    private val _currentIndex = MutableStateFlow(0)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    @Volatile private var segments: List<Segment> = emptyList()
    @Volatile private var editClips: List<Clip> = emptyList()
    @Volatile private var sourceMode = false
    @Volatile private var muted = false

    /** Timeline position while paused; while playing it is computed from the clock. */
    private val pausedAt = AtomicLong(0L)
    @Volatile private var playing = false
    @Volatile private var clockStartNanos = 0L
    @Volatile private var clockStartUs = 0L
    /** Bumped on every command so running playback loops stop. */
    private val generation = AtomicLong(0L)

    private val videoThread = Executors.newSingleThreadExecutor { r -> Thread(r, "preview-video").apply { isDaemon = true } }
    private val audioThread = Executors.newSingleThreadExecutor { r -> Thread(r, "preview-audio").apply { isDaemon = true } }
    private val converter = Java2DFrameConverter()

    /** Music and voice-overs; each plays on its own thread and sound line, on the same clock. */
    @Volatile private var audioClips: List<AudioClip> = emptyList()
    private val musicPool = Executors.newCachedThreadPool { r -> Thread(r, "preview-music").apply { isDaemon = true } }

    /** Open decoder for still frames while scrubbing, reused for seeks in the same file. */
    private var stillGrabber: FFmpegFrameGrabber? = null
    private var stillPath: String? = null
    private var stillRotation = 0

    @Volatile private var pendingSeek: Long? = null

    // ---------------- Playlist ----------------

    private fun segmentsFor(clips: List<Clip>): List<Segment> {
        val starts = Timeline.startsUs(clips)
        return clips.mapIndexed { i, c ->
            Segment(
                path = c.sourceUri,
                sourceStartUs = c.trimStartUs,
                sourceEndUs = c.trimEndUs,
                speed = c.effectiveSpeed,
                volume = c.volume,
                hasAudio = c.hasAudio && !c.isPhoto,
                isPhoto = c.isPhoto,
                timelineStartUs = starts[i],
            )
        }
    }

    private fun totalUs(): Long = segments.lastOrNull()?.timelineEndUs ?: 0L

    private fun segmentAt(timeUs: Long): Int {
        val list = segments
        if (list.isEmpty()) return 0
        val i = list.indexOfFirst { timeUs < it.timelineEndUs }
        return if (i < 0) list.lastIndex else i
    }

    override fun setClips(clips: List<Clip>, keepTimeUs: Long, force: Boolean) {
        val newSegments = segmentsFor(clips)
        val changed = force || sourceMode || newSegments != segments
        editClips = clips
        sourceMode = false
        if (!changed) return
        val wasPlaying = playing
        stop()
        segments = newSegments
        val t = keepTimeUs.coerceIn(0L, totalUs())
        pausedAt.set(t)
        _currentIndex.value = segmentAt(t)
        requestStill(t)
        if (wasPlaying) play()
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    override fun setAudio(clips: List<AudioClip>) {
        if (clips == audioClips) return
        audioClips = clips
        // Playing: restart from here so the new mix is heard straight away.
        if (playing && !sourceMode) seekInternal(now())
    }

    // ---------------- Clock ----------------

    private fun now(): Long =
        if (playing) clockStartUs + (System.nanoTime() - clockStartNanos) / 1000 else pausedAt.get()

    override fun positionUs(): Long = if (sourceMode) 0L else now().coerceIn(0L, totalUs())

    override fun sourcePositionUs(): Long = if (sourceMode) now().coerceIn(0L, totalUs()) else 0L

    override val isPlaying: Boolean get() = playing

    // ---------------- Commands ----------------

    override fun seekTo(timeUs: Long, exact: Boolean) {
        if (sourceMode) return
        seekInternal(timeUs)
    }

    override fun seekSource(timeUs: Long, exact: Boolean) {
        if (!sourceMode) return
        seekInternal(timeUs)
    }

    private fun seekInternal(timeUs: Long) {
        val wasPlaying = playing
        stop()
        val t = timeUs.coerceIn(0L, totalUs())
        pausedAt.set(t)
        _currentIndex.value = segmentAt(t)
        requestStill(t)
        if (wasPlaying) play()
    }

    override fun play() {
        if (playing || segments.isEmpty()) return
        var start = pausedAt.get()
        if (start >= totalUs() - 20_000) start = 0L
        val gen = generation.incrementAndGet()
        clockStartUs = start
        clockStartNanos = System.nanoTime()
        playing = true
        videoThread.execute { runCatching { videoLoop(gen, start) } }
        audioThread.execute { runCatching { audioLoop(gen, start) } }
        if (!sourceMode) {
            audioClips.filter { it.endUs > start }.forEach { c ->
                musicPool.execute { runCatching { musicLoop(gen, c, start) } }
            }
        }
    }

    override fun pause() {
        if (!playing) return
        stop()
    }

    private fun stop() {
        if (playing) pausedAt.set(now().coerceIn(0L, totalUs()))
        playing = false
        generation.incrementAndGet()
    }

    override fun enterSource(clip: Clip, atUs: Long) {
        stop()
        sourceMode = true
        segments = listOf(
            Segment(
                path = clip.sourceUri,
                sourceStartUs = 0L,
                sourceEndUs = clip.sourceDurationUs,
                speed = 1f,
                volume = 1f,
                hasAudio = clip.hasAudio,
                isPhoto = clip.isPhoto,
                timelineStartUs = 0L,
            )
        )
        val t = atUs.coerceIn(0L, clip.sourceDurationUs)
        pausedAt.set(t)
        _currentIndex.value = 0
        requestStill(t)
    }

    override fun exitSource(clips: List<Clip>, keepTimeUs: Long) {
        stop()
        setClips(clips, keepTimeUs, force = true)
    }

    override fun release() {
        stop()
        videoThread.execute {
            runCatching { stillGrabber?.close() }
            stillGrabber = null
        }
        videoThread.shutdown()
        audioThread.shutdown()
        musicPool.shutdown()
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        val img by frame.collectAsState()
        val current = img
        if (current != null) {
            Image(current, null, contentScale = ContentScale.FillBounds, modifier = modifier)
        } else {
            Box(modifier)
        }
    }

    // ---------------- Still frames (paused / scrubbing) ----------------

    /** Only the newest request is decoded, so fast scrubbing never queues up. */
    private fun requestStill(timeUs: Long) {
        val first = pendingSeek == null
        pendingSeek = timeUs
        if (first) {
            videoThread.execute {
                val t = pendingSeek ?: return@execute
                pendingSeek = null
                runCatching { decodeStill(t) }
            }
        }
    }

    private fun decodeStill(timeUs: Long) {
        val list = segments
        if (list.isEmpty()) {
            frame.value = null
            return
        }
        val seg = list[segmentAt(timeUs)]
        if (seg.isPhoto) {
            frame.value = null // the photo itself is drawn by the canvas preview
            return
        }
        val sourceUs = seg.sourceStartUs + ((timeUs - seg.timelineStartUs) * seg.speed).toLong()
        val g = stillFor(seg.path) ?: return
        g.setTimestamp(sourceUs.coerceIn(0L, (g.lengthInTime - 1).coerceAtLeast(0L)), true)
        val f = g.grabImage() ?: return
        Ffmpeg.frameToImage(f, converter, stillRotation)?.let { frame.value = it.toComposeImageBitmap() }
    }

    private fun stillFor(path: String): FFmpegFrameGrabber? {
        if (stillPath == path && stillGrabber != null) return stillGrabber
        runCatching { stillGrabber?.close() }
        stillGrabber = null
        stillPath = null
        return runCatching { openVideo(path) }.getOrNull()?.also { (g, rot) ->
            stillGrabber = g
            stillPath = path
            stillRotation = rot
        }?.first
    }

    private fun openVideo(path: String): Pair<FFmpegFrameGrabber, Int> {
        Ffmpeg.init()
        val g = FFmpegFrameGrabber(path)
        g.start()
        val (w, h) = Ffmpeg.decodeSize(g.imageWidth, g.imageHeight, PREVIEW_MAX_SIDE)
        g.imageWidth = w
        g.imageHeight = h
        return g to Ffmpeg.rotationOf(g)
    }

    // ---------------- Playback ----------------

    private fun alive(gen: Long) = generation.get() == gen && playing

    /** Sleeps until timeline time [timeUs] on the playback clock. Returns false if stopped. */
    private fun waitUntil(gen: Long, timeUs: Long): Boolean {
        while (alive(gen)) {
            val aheadUs = timeUs - now()
            if (aheadUs <= 0) return true
            Thread.sleep((aheadUs / 1000).coerceIn(1L, 20L))
        }
        return false
    }

    private fun videoLoop(gen: Long, startUs: Long) {
        val list = segments
        var i = list.indexOfFirst { startUs < it.timelineEndUs }.takeIf { it >= 0 } ?: return
        var from = startUs
        while (i < list.size && alive(gen)) {
            val seg = list[i]
            _currentIndex.value = i
            if (seg.isPhoto) {
                frame.value = null
                if (!waitUntil(gen, seg.timelineEndUs)) return
            } else {
                playVideoSegment(gen, seg, from)
            }
            if (!alive(gen)) return
            i++
            if (i < list.size) from = list[i].timelineStartUs
        }
        if (alive(gen)) {
            // Reached the end: stop on the last frame.
            pausedAt.set(totalUs())
            playing = false
        }
    }

    private fun playVideoSegment(gen: Long, seg: Segment, fromTimelineUs: Long) {
        val (g, rotation) = runCatching { openVideo(seg.path) }.getOrNull() ?: run {
            waitUntil(gen, seg.timelineEndUs)
            return
        }
        try {
            val startSource = seg.sourceStartUs + ((fromTimelineUs - seg.timelineStartUs) * seg.speed).toLong()
            g.setTimestamp(startSource, true)
            while (alive(gen)) {
                val f = g.grabImage() ?: break
                val ts = g.timestamp
                if (ts >= seg.sourceEndUs) break
                val due = seg.timelineStartUs + ((ts - seg.sourceStartUs) / seg.speed).toLong()
                // Drop frames we're already late for (more than 80 ms) to keep up.
                if (due < now() - 80_000) continue
                val img = Ffmpeg.frameToImage(f, converter, rotation) ?: continue
                val bitmap = img.toComposeImageBitmap()
                if (!waitUntil(gen, due)) break
                frame.value = bitmap
            }
            // Hold the last frame until the segment's time is up.
            waitUntil(gen, seg.timelineEndUs)
        } finally {
            runCatching { g.close() }
        }
    }

    private fun audioLoop(gen: Long, startUs: Long) {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)
        val line: SourceDataLine = runCatching {
            (AudioSystem.getLine(javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine)
                .apply { open(format, SAMPLE_RATE / 5 * 4) }
        }.getOrNull() ?: return
        line.start()
        try {
            val list = segments
            var i = list.indexOfFirst { startUs < it.timelineEndUs }.takeIf { it >= 0 } ?: return
            var from = startUs
            while (i < list.size && alive(gen)) {
                val seg = list[i]
                // Sound follows speed only on export; muted, silent and sped-up clips play silence here.
                val audible = !muted && seg.hasAudio && seg.volume > 0f && seg.speed == 1f
                if (!audible) {
                    if (!waitUntil(gen, seg.timelineEndUs)) return
                } else {
                    playAudioSegment(gen, seg, from, line)
                }
                i++
                if (i < list.size) from = list[i].timelineStartUs
            }
        } finally {
            runCatching {
                line.stop()
                line.flush()
                line.close()
            }
        }
    }

    /** Plays one music/voice clip from timeline time [fromUs] (or its own start, if later). */
    private fun musicLoop(gen: Long, clip: AudioClip, fromUs: Long) {
        Ffmpeg.init()
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)
        val line: SourceDataLine = runCatching {
            (AudioSystem.getLine(javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine)
                .apply { open(format, SAMPLE_RATE / 5 * 4) }
        }.getOrNull() ?: return
        val g = FFmpegFrameGrabber(clip.sourceUri)
        try {
            g.sampleRate = SAMPLE_RATE
            g.audioChannels = 2
            g.sampleFormat = avutil.AV_SAMPLE_FMT_S16
            g.start()
            val beginUs = maxOf(fromUs, clip.startUs)
            g.setAudioTimestamp(clip.trimStartUs + (beginUs - clip.startUs))
            // Line up with the clock before writing the first samples.
            if (!waitUntil(gen, beginUs)) return
            line.start()
            var framesWritten = 0L
            var bytes = ByteArray(0)
            while (alive(gen)) {
                val f = g.grabSamples() ?: break
                if (g.timestamp >= clip.trimEndUs) break
                val buf = f.samples?.firstOrNull() as? ShortBuffer ?: continue
                val n = buf.remaining()
                val frames = n / 2
                if (frames == 0) continue
                if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                // Preview is capped at 100%; the gain ramps smoothly across each block.
                val t0 = beginUs + framesWritten * 1_000_000L / SAMPLE_RATE
                val t1 = beginUs + (framesWritten + frames) * 1_000_000L / SAMPLE_RATE
                val g0 = AudioMath.gain(clip, t0).coerceIn(0f, 1f)
                val g1 = AudioMath.gain(clip, t1 - 1).coerceIn(0f, 1f)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until n) {
                    val gain = g0 + (g1 - g0) * ((k / 2).toFloat() / frames)
                    bb.putShort((buf.get(buf.position() + k) * gain).roundToInt().toShort())
                }
                line.write(bytes, 0, n * 2)
                framesWritten += frames
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { g.close() }
            runCatching {
                line.stop()
                line.flush()
                line.close()
            }
        }
    }

    private fun playAudioSegment(gen: Long, seg: Segment, fromTimelineUs: Long, line: SourceDataLine) {
        Ffmpeg.init()
        val g = FFmpegFrameGrabber(seg.path)
        try {
            g.sampleRate = SAMPLE_RATE
            g.audioChannels = 2
            g.sampleFormat = avutil.AV_SAMPLE_FMT_S16
            g.start()
            val startSource = seg.sourceStartUs + (fromTimelineUs - seg.timelineStartUs)
            g.setAudioTimestamp(startSource)
            // Line up with the clock before writing the first samples.
            if (!waitUntil(gen, fromTimelineUs)) return
            val volume = seg.volume.coerceIn(0f, 1f)
            var bytes = ByteArray(0)
            while (alive(gen)) {
                val f = g.grabSamples() ?: break
                if (g.timestamp >= seg.sourceEndUs) break
                val buf = f.samples?.firstOrNull() as? ShortBuffer ?: continue
                val n = buf.remaining()
                if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until n) {
                    val v = buf.get(buf.position() + k)
                    bb.putShort(if (volume >= 0.999f) v else (v * volume).roundToInt().toShort())
                }
                line.write(bytes, 0, n * 2)
            }
            waitUntil(gen, seg.timelineEndUs)
        } catch (_: Throwable) {
            waitUntil(gen, seg.timelineEndUs)
        } finally {
            runCatching { g.close() }
        }
    }

    companion object {
        private const val PREVIEW_MAX_SIDE = 960
        private const val SAMPLE_RATE = 44_100
    }
}
