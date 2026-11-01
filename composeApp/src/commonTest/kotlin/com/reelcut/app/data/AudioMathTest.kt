package com.reelcut.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioMathTest {
    private fun a(
        id: String,
        start: Long,
        len: Long,
        volume: Float = 1f,
        fadeIn: Long = 0,
        fadeOut: Long = 0,
        trimStart: Long = 0,
        source: Long = 60_000_000,
    ) = AudioClip(
        id = id,
        sourceUri = "x",
        sourceDurationUs = source,
        trimStartUs = trimStart,
        trimEndUs = trimStart + len,
        startUs = start,
        volume = volume,
        fadeInUs = fadeIn,
        fadeOutUs = fadeOut,
    )

    @Test fun lanesStackOnlyWhenOverlapping() {
        val lanes = AudioMath.lanes(listOf(a("a", 0, 3_000_000), a("b", 1_000_000, 3_000_000), a("c", 3_000_000, 1_000_000)))
        assertEquals(0, lanes["a"])
        assertEquals(1, lanes["b"])
        assertEquals(0, lanes["c"])
    }

    @Test fun gainFollowsVolumeAndFades() {
        val c = a("a", 1_000_000, 10_000_000, volume = 0.8f, fadeIn = 2_000_000, fadeOut = 4_000_000)
        assertEquals(0f, AudioMath.gain(c, 500_000))
        assertEquals(0f, AudioMath.gain(c, 1_000_000))
        assertEquals(0.4f, AudioMath.gain(c, 2_000_000), 1e-4f)   // halfway up the fade-in
        assertEquals(0.8f, AudioMath.gain(c, 5_000_000), 1e-4f)   // steady
        assertEquals(0.4f, AudioMath.gain(c, 9_000_000), 1e-4f)   // halfway down the fade-out
        assertEquals(0f, AudioMath.gain(c, 11_000_000))           // end is exclusive
    }

    @Test fun fadesNeverExceedClip() {
        val c = AudioMath.clampFades(a("a", 0, 4_000_000, fadeIn = 3_000_000, fadeOut = 3_000_000))
        assertEquals(3_000_000L, c.fadeInUs)
        assertEquals(1_000_000L, c.fadeOutUs)
        val big = AudioMath.clampFades(a("b", 0, 60_000_000, fadeIn = 30_000_000))
        assertEquals(AudioClip.MAX_FADE_US, big.fadeInUs)
    }

    @Test fun fitCutsAtVideoEndAndDropsLateClips() {
        val total = 10_000_000L
        val fitted = AudioMath.fit(
            listOf(
                a("in", 0, 5_000_000),
                a("long", 8_000_000, 20_000_000, trimStart = 2_000_000),
                a("late", 10_000_000, 3_000_000),
                a("quiet", 1_000_000, 3_000_000, volume = 0f),
                a("sliver", 9_950_000, 3_000_000),
            ),
            total,
        )
        assertEquals(listOf("in", "long"), fitted.map { it.id })
        val long = fitted[1]
        assertEquals(total, long.endUs)
        assertEquals(2_000_000L, long.trimStartUs)
        assertEquals(4_000_000L, long.trimEndUs)
    }

    @Test fun fitKeepsFadeOutAtTheCut() {
        val c = a("a", 6_000_000, 20_000_000, fadeOut = 3_000_000)
        val f = AudioMath.fit(listOf(c), 10_000_000).single()
        assertEquals(4_000_000L, f.durationUs)
        assertEquals(3_000_000L, f.fadeOutUs)
        assertEquals(0f, AudioMath.gain(f, 10_000_000))
        assertTrue(AudioMath.gain(f, 9_000_000) < 0.5f)
    }

    @Test fun placeAtKeepsSomethingAudible() {
        assertEquals(2_000_000L, AudioMath.placeAt(2_000_000, 10_000_000))
        assertEquals(9_900_000L, AudioMath.placeAt(10_000_000, 10_000_000))
        assertEquals(0L, AudioMath.placeAt(5_000_000, 0))
    }

    @Test fun normaliseKeepsTrimStartAndVolumeInRange() {
        val c = a("a", 20_000_000, 5_000_000, volume = 5f).copy(trimStartUs = 59_990_000, trimEndUs = 70_000_000)
        val n = AudioMath.normalise(c, 10_000_000)
        assertEquals(2f, n.volume)
        assertEquals(9_900_000L, n.startUs)
        assertTrue(n.trimEndUs <= n.sourceDurationUs)
        assertTrue(n.trimEndUs - n.trimStartUs >= AudioClip.MIN_US)
        assertNull(AudioMath.fit(listOf(n.copy(volume = 0f)), 10_000_000).firstOrNull())
    }
}
