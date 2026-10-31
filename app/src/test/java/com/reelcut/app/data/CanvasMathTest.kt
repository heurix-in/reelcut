package com.reelcut.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasMathTest {
    private fun clip(w: Int, h: Int, rotation: Int = 0, fill: Boolean = false) =
        Clip(id = "c", sourceUri = "u", sourceDurationUs = 1_000_000, width = w, height = h,
            trimEndUs = 1_000_000, rotation = rotation, fill = fill)

    @Test fun originalFollowsFirstClip() {
        val portrait = listOf(clip(1080, 1920))
        assertEquals(CanvasMath.Size(1080, 1920), CanvasMath.outputSize(portrait, Canvas(), 1080))
        assertEquals(CanvasMath.Size(720, 1280), CanvasMath.outputSize(portrait, Canvas(), 720))
        val landscapeRotated = listOf(clip(1920, 1080, rotation = 90))
        assertEquals(CanvasMath.Size(1080, 1920), CanvasMath.outputSize(landscapeRotated, Canvas(), 1080))
    }

    @Test fun fixedAspects() {
        val clips = listOf(clip(1920, 1080))
        assertEquals(CanvasMath.Size(1080, 1920), CanvasMath.outputSize(clips, Canvas(Aspect.R9_16), 1080))
        assertEquals(CanvasMath.Size(1080, 1080), CanvasMath.outputSize(clips, Canvas(Aspect.R1_1), 1080))
        assertEquals(CanvasMath.Size(1080, 1350), CanvasMath.outputSize(clips, Canvas(Aspect.R4_5), 1080))
        assertEquals(CanvasMath.Size(1280, 720), CanvasMath.outputSize(clips, Canvas(Aspect.R16_9), 720))
    }

    @Test fun fitAndFill() {
        // Landscape clip in a square box: fit is limited by width, fill by height.
        val fit = CanvasMath.contentSize(clip(200, 100), 100f, 100f)
        assertEquals(100f, fit.width, 0.01f)
        assertEquals(50f, fit.height, 0.01f)
        val fill = CanvasMath.contentSize(clip(200, 100, fill = true), 100f, 100f)
        assertEquals(200f, fill.width, 0.01f)
        assertEquals(100f, fill.height, 0.01f)
        // Rotated 90: the 200x100 clip stands 100 wide x 200 tall, so fit scale is 0.5.
        val rotated = CanvasMath.contentSize(clip(200, 100, rotation = 90), 100f, 100f)
        assertEquals(100f, rotated.width, 0.01f)
        assertEquals(50f, rotated.height, 0.01f)
    }

    @Test fun speedChangesTimelineDuration() {
        val c = clip(100, 100).copy(trimEndUs = 4_000_000, sourceDurationUs = 4_000_000, speed = 2f)
        assertEquals(2_000_000L, c.durationUs)
        val photo = c.copy(kind = ClipKind.PHOTO)
        assertEquals(4_000_000L, photo.durationUs)
        // Splitting a 2x clip 1 s into the timeline cuts the source at 2 s.
        val out = Timeline.split(listOf(c), 1_000_000, "b")!!
        assertEquals(2_000_000L, out[0].trimEndUs)
        assertEquals(2_000_000L, out[1].trimStartUs)
    }
}
