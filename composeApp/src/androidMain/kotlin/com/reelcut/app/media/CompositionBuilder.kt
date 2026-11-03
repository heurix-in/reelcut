package com.reelcut.app.media

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioMath
import com.reelcut.app.data.Canvas
import com.reelcut.app.data.CanvasMath
import com.reelcut.app.data.Clip
import com.reelcut.app.data.LookMath
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Quality
import java.io.File

/**
 * Turns the Project's clips into Media3 objects. Preview and export both start from
 * [mediaItem], so the trim points the player shows are exactly the ones exported.
 */
object CompositionBuilder {

    fun mediaItem(clip: Clip): MediaItem {
        val b = MediaItem.Builder()
            .setUri(Uri.parse(clip.sourceUri))
            .setMediaId(clip.id)
        if (clip.isPhoto) {
            b.setMimeType(clip.mimeType ?: "image/jpeg")
            b.setImageDurationMs((clip.trimEndUs - clip.trimStartUs) / 1000)
        } else {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartUs / 1000)
                    .setEndPositionMs(clip.trimEndUs / 1000)
                    .build()
            )
        }
        return b.build()
    }

    fun previewItems(clips: List<Clip>): List<MediaItem> = clips.map(::mediaItem)

    fun outputSize(clips: List<Clip>, canvas: Canvas, settings: ExportSettings): CanvasMath.Size =
        CanvasMath.outputSize(clips, canvas, settings.height)

    fun bitrate(size: CanvasMath.Size, settings: ExportSettings): Int = CanvasMath.bitrate(size, settings)

    /** A text/logo layer as an Android bitmap, shown between two timeline times. */
    class Layer(val bitmap: Bitmap, val startUs: Long, val endUs: Long)

    fun exportComposition(
        clips: List<Clip>,
        canvas: Canvas,
        settings: ExportSettings,
        muteOriginal: Boolean = false,
        layers: List<Layer> = emptyList(),
        audio: List<AudioClip> = emptyList(),
        tempDir: File? = null,
    ): Composition {
        val size = outputSize(clips, canvas, settings)
        val starts = com.reelcut.app.data.Timeline.startsUs(clips)
        val items = clips.mapIndexed { i, clip ->
            editedItem(clip, canvas, settings, size, muteOriginal, layers, starts[i])
        }
        val sequences = mutableListOf(EditedMediaItemSequence(items))
        if (audio.isNotEmpty() && tempDir != null) sequences += audioSequences(audio, tempDir)
        return Composition.Builder(sequences)
            // Clips with no sound (and photos) get silence, so mixed clips join cleanly.
            .experimentalSetForceAudioTrack(true)
            .build()
    }

    /**
     * Music and voice-overs, mixed with the video's sound by Transformer. Sounds that overlap each
     * other go in separate sequences; inside a sequence a stretch of silence (a small WAV file)
     * stands in for the wait before each sound starts.
     */
    private fun audioSequences(audio: List<AudioClip>, tempDir: File): List<EditedMediaItemSequence> {
        tempDir.mkdirs()
        val lanes = AudioMath.lanes(audio)
        var silenceCount = 0
        return audio.groupBy { lanes[it.id] ?: 0 }.toSortedMap().values.map { lane ->
            var cursorUs = 0L
            val items = mutableListOf<EditedMediaItem>()
            lane.sortedBy { it.startUs }.forEach { c ->
                val gapUs = c.startUs - cursorUs
                if (gapUs > MIN_GAP_US) {
                    val wav = File(tempDir, "silence${silenceCount++}.wav")
                    SilenceWav.write(wav, gapUs)
                    items += EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav)))
                        .setRemoveVideo(true)
                        .build()
                }
                items += audioItem(c)
                cursorUs = c.endUs
            }
            EditedMediaItemSequence(items)
        }
    }

    private fun audioItem(c: AudioClip): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(c.sourceUri))
            .setMediaId(c.id)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.trimStartUs / 1000)
                    .setEndPositionMs(c.trimEndUs / 1000)
                    .build()
            )
            .build()
        val processors = mutableListOf<AudioProcessor>()
        if (c.volume != 1f || c.fadeInUs > 0 || c.fadeOutUs > 0) processors += FadeVolumeProcessor(c)
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setEffects(Effects(processors, emptyList()))
            .build()
    }

    private const val MIN_GAP_US = 10_000L

    private fun editedItem(
        clip: Clip,
        canvas: Canvas,
        settings: ExportSettings,
        size: CanvasMath.Size,
        muteOriginal: Boolean,
        layers: List<Layer>,
        clipStartUs: Long,
    ): EditedMediaItem {
        val audio = mutableListOf<AudioProcessor>()
        val video = mutableListOf<Effect>()

        if (!clip.isPhoto && clip.speed != 1f) {
            val speed = clip.speed
            val pair = Effects.createExperimentalSpeedChangingEffect(object : SpeedProvider {
                override fun getSpeed(timeUs: Long): Float = speed
                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
            })
            audio += pair.first
            video += pair.second
        }
        if (!clip.isPhoto && clip.volume > 0f && clip.volume != 1f) {
            audio += volumeProcessor(clip.volume)
        }

        // User rotation (clockwise) and mirror, applied before framing.
        if (clip.rotation != 0 || clip.flipH) {
            video += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(-clip.rotation.toFloat()) // Media3 rotates counter-clockwise
                .setScale(if (clip.flipH) -1f else 1f, 1f)
                .build()
        }

        // Colour look: before framing, so it covers the picture and its blurred copy but not the bars.
        if (!clip.look.isNeutral) video += LookMatrix(LookMath.rgbMatrix(LookMath.affine(clip.look)))

        if (canvas.blurBackground && !clip.fill) {
            // Stretch the frame to the canvas, blur it, and lay the sharp frame on top at its true shape.
            video += Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_STRETCH_TO_FIT)
            val content = CanvasMath.contentSize(clip, size.width.toFloat(), size.height.toFloat())
            val sharpW = if (clip.rotation % 180 == 0) content.width else content.height
            val sharpH = if (clip.rotation % 180 == 0) content.height else content.width
            video += GaussianBlurWithFrameOverlaid(
                /* sigma = */ 30f,
                /* scaleSharpX = */ (sharpW / size.width).coerceIn(0.01f, 1f),
                /* scaleSharpY = */ (sharpH / size.height).coerceIn(0.01f, 1f),
            )
        } else {
            val layout = if (clip.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
            video += Presentation.createForWidthAndHeight(size.width, size.height, layout)
        }
        if (settings.fps <= 30 && !clip.isPhoto) {
            video += FrameDropEffect.createDefaultFrameDropEffect(settings.fps.toFloat())
        }

        // Coloured background (covers the bars around the video), then text and logos on top.
        val overlays = mutableListOf<TextureOverlay>()
        if (!canvas.blurBackground && !clip.fill && canvas.backgroundArgb != 0xFF000000L) {
            overlays += BitmapOverlay.createStaticBitmapOverlay(backgroundBitmap(clip, canvas, size))
        }
        val clipEndUs = clipStartUs + clip.durationUs
        layers.filter { it.endUs > clipStartUs && it.startUs < clipEndUs }.forEach {
            overlays += TimedOverlay(it.bitmap, it.startUs, it.endUs, clipStartUs)
        }
        if (overlays.isNotEmpty()) video += OverlayEffect(ImmutableList.copyOf(overlays))

        val b = EditedMediaItem.Builder(mediaItem(clip))
            .setEffects(Effects(audio, video))
        if (clip.isPhoto) {
            b.setDurationUs(clip.trimEndUs - clip.trimStartUs)
            b.setFrameRate(settings.fps)
        }
        if (!clip.isPhoto && (muteOriginal || clip.volume <= 0f || !clip.hasAudio)) {
            b.setRemoveAudio(true)
        }
        return b.build()
    }

    /** Canvas-sized bitmap in the background colour with a clear hole where the video sits. */
    private fun backgroundBitmap(clip: Clip, canvas: Canvas, size: CanvasMath.Size): Bitmap {
        val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(canvas.backgroundArgb.toInt())
        val content = CanvasMath.contentSize(clip, size.width.toFloat(), size.height.toFloat())
        val w = if (clip.rotation % 180 == 0) content.width else content.height
        val h = if (clip.rotation % 180 == 0) content.height else content.width
        val left = (size.width - w) / 2f + 1f
        val top = (size.height - h) / 2f + 1f
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        c.drawRect(left, top, left + w - 2f, top + h - 2f, clear)
        return bmp
    }

    /**
     * A full-frame layer visible between two timeline times. Each clip gets its own copy; the
     * first frame it sees is the clip's first frame, so time = clip start + elapsed.
     */
    private class TimedOverlay(
        private val bitmap: Bitmap,
        private val startUs: Long,
        private val endUs: Long,
        private val clipStartUs: Long,
    ) : BitmapOverlay() {
        private var firstUs = Long.MIN_VALUE

        private fun timelineUs(presentationTimeUs: Long): Long {
            if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
            return clipStartUs + (presentationTimeUs - firstUs)
        }

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            timelineUs(presentationTimeUs)
            return bitmap
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val t = timelineUs(presentationTimeUs)
            val visible = t in startUs until endUs
            return OverlaySettings.Builder().setAlphaScale(if (visible) 1f else 0f).build()
        }
    }

    /** A clip's colour look as one RGB matrix (see [LookMath.rgbMatrix]); the same for every frame. */
    private class LookMatrix(private val matrix: FloatArray) : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
    }

    /** Scales every channel by [volume] (mono and stereo sources). */
    private fun volumeProcessor(volume: Float): AudioProcessor =
        ChannelMixingAudioProcessor().apply {
            for (channels in 1..2) {
                putChannelMixingMatrix(ChannelMixingMatrix.create(channels, channels).scaleBy(volume))
            }
        }
}
