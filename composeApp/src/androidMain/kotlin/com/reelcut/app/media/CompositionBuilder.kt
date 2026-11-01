package com.reelcut.app.media

import android.net.Uri
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
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.reelcut.app.data.Canvas
import com.reelcut.app.data.CanvasMath
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Quality

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

    fun exportComposition(clips: List<Clip>, canvas: Canvas, settings: ExportSettings, muteOriginal: Boolean = false): Composition {
        val size = outputSize(clips, canvas, settings)
        val items = clips.map { clip -> editedItem(clip, canvas, settings, size, muteOriginal) }
        return Composition.Builder(EditedMediaItemSequence(items))
            // Clips with no sound (and photos) get silence, so mixed clips join cleanly.
            .experimentalSetForceAudioTrack(true)
            .build()
    }

    private fun editedItem(clip: Clip, canvas: Canvas, settings: ExportSettings, size: CanvasMath.Size, muteOriginal: Boolean): EditedMediaItem {
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

    /** Scales every channel by [volume] (mono and stereo sources). */
    private fun volumeProcessor(volume: Float): AudioProcessor =
        ChannelMixingAudioProcessor().apply {
            for (channels in 1..2) {
                putChannelMixingMatrix(ChannelMixingMatrix.create(channels, channels).scaleBy(volume))
            }
        }
}
