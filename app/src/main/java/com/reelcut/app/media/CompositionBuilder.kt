package com.reelcut.app.media

import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Quality
import kotlin.math.roundToInt

/**
 * Turns the Project's clips into Media3 objects. Preview and export both start from
 * [mediaItem], so the trim points the player shows are exactly the ones exported.
 */
object CompositionBuilder {

    fun mediaItem(clip: Clip): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(clip.sourceUri))
            .setMediaId(clip.id)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartUs / 1000)
                    .setEndPositionMs(clip.trimEndUs / 1000)
                    .build()
            )
            .build()

    fun previewItems(clips: List<Clip>): List<MediaItem> = clips.map(::mediaItem)

    data class Size(val width: Int, val height: Int)

    /**
     * Output frame size. The canvas follows the first clip's shape (portrait or landscape);
     * [ExportSettings.height] is the short side, e.g. 1080 gives 1080x1920 for portrait.
     */
    fun outputSize(clips: List<Clip>, settings: ExportSettings): Size {
        val first = clips.firstOrNull { it.width > 0 && it.height > 0 }
        val w0 = first?.width ?: 1080
        val h0 = first?.height ?: 1920
        val short = settings.height
        val (w, h) = if (w0 >= h0) {
            (short.toDouble() * w0 / h0).roundToInt() to short
        } else {
            short to (short.toDouble() * h0 / w0).roundToInt()
        }
        return Size(even(w), even(h))
    }

    private fun even(v: Int) = (v / 2) * 2

    fun bitrate(size: Size, settings: ExportSettings): Int {
        val bitsPerPixel = when (settings.quality) {
            Quality.LOW -> 0.05
            Quality.MEDIUM -> 0.08
            Quality.HIGH -> 0.12
        }
        return (size.width.toDouble() * size.height * settings.fps * bitsPerPixel)
            .toInt()
            .coerceIn(1_000_000, 40_000_000)
    }

    fun exportComposition(clips: List<Clip>, settings: ExportSettings): Composition {
        val size = outputSize(clips, settings)
        val videoEffects = buildList<Effect> {
            // Every clip is scaled to fit the same frame, with black bars where shapes differ.
            add(Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT))
            if (settings.fps <= 30) add(FrameDropEffect.createDefaultFrameDropEffect(settings.fps.toFloat()))
        }
        val items = clips.map { clip ->
            EditedMediaItem.Builder(mediaItem(clip))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()
        }
        return Composition.Builder(EditedMediaItemSequence(items))
            // Clips with no sound get silence, so mixed clips join cleanly.
            .experimentalSetForceAudioTrack(true)
            .build()
    }
}
