package com.reelcut.app.data

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LookMathTest {
    private fun near(expected: Float, actual: Float, eps: Float = 1e-4f) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    private fun apply(look: Look, r: Float, g: Float, b: Float) = LookMath.apply(LookMath.affine(look), r, g, b)

    @Test
    fun noLookLeavesEveryColourAlone() {
        val a = LookMath.affine(Look())
        assertEquals(listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), a.m.toList())
        assertEquals(listOf(0f, 0f, 0f), a.o.toList())
        val c = apply(Look(), 0.2f, 0.6f, 0.9f)
        near(0.2f, c[0]); near(0.6f, c[1]); near(0.9f, c[2])
        assertTrue(Look().isNeutral)
    }

    @Test
    fun brightnessShiftsEverythingUpAndWhiteStaysWhite() {
        val c = apply(Look(brightness = 1f), 0f, 0f, 0f)
        near(0.3f, c[0]); near(0.3f, c[1]); near(0.3f, c[2])
        assertEquals(listOf(1f, 1f, 1f), apply(Look(brightness = 1f), 1f, 1f, 1f).toList())
        near(0f, apply(Look(brightness = -1f), 0.2f, 0.2f, 0.2f)[0])
    }

    @Test
    fun contrastStretchesAroundMidGrey() {
        val mid = apply(Look(contrast = 0.8f), 0.5f, 0.5f, 0.5f)
        near(0.5f, mid[0]); near(0.5f, mid[1]); near(0.5f, mid[2])
        near(0.875f, apply(Look(contrast = 0.5f), 0.75f, 0.75f, 0.75f)[0]) // 0.5 + 0.25 * 1.5
        near(0.625f, apply(Look(contrast = -0.5f), 0.75f, 0.75f, 0.75f)[0]) // 0.5 + 0.25 * 0.5
    }

    @Test
    fun fullDesaturationGivesGreyOfTheSameBrightness() {
        val c = apply(Look(saturation = -1f), 1f, 0f, 0f)
        near(0.2126f, c[0]); near(0.2126f, c[1]); near(0.2126f, c[2])
    }

    @Test
    fun saturationNeverTintsGrey() {
        for (s in listOf(-1f, -0.5f, 0.5f, 1f)) {
            val c = apply(Look(saturation = s), 0.4f, 0.4f, 0.4f)
            near(0.4f, c[0]); near(0.4f, c[1]); near(0.4f, c[2])
        }
    }

    @Test
    fun warmthAddsRedAndTakesAwayBlue() {
        val warm = apply(Look(warmth = 1f), 0.5f, 0.5f, 0.5f)
        assertTrue(warm[0] > 0.5f && warm[2] < 0.5f)
        near(0.5f, warm[1])
        val cool = apply(Look(warmth = -1f), 0.5f, 0.5f, 0.5f)
        assertTrue(cool[0] < 0.5f && cool[2] > 0.5f)
    }

    @Test
    fun valuesOutsideTheRangeAreHeldInside() {
        assertEquals(Look(1f, -1f, 1f, -1f), Look(5f, -5f, 9f, -9f).coerced())
        assertEquals(LookMath.affine(Look(1f, 1f, 1f, 1f)).m.toList(), LookMath.affine(Look(7f, 7f, 7f, 7f)).m.toList())
    }

    private val samples = listOf(
        Triple(0.1f, 0.4f, 0.8f), Triple(0.9f, 0.2f, 0.3f), Triple(0.5f, 0.5f, 0.5f), Triple(0f, 1f, 0f), Triple(0.7f, 0.7f, 0.1f),
    )
    private val looks = listOf(
        Look(brightness = 0.4f), Look(contrast = 0.6f, saturation = 0.5f), Look(warmth = -0.7f, brightness = -0.2f),
        Look(1f, 1f, 1f, 1f), Look(-1f, -1f, -1f, -1f), LookPresets.all[3].look,
    )

    @Test
    fun theComposeColourMatrixGivesTheSameColours() {
        for (look in looks) for ((r, g, b) in samples) {
            val expected = apply(look, r, g, b)
            val cm = LookMath.colorMatrix(LookMath.affine(look))
            assertEquals(20, cm.size)
            for (row in 0..2) {
                val v = (cm[row * 5] * r + cm[row * 5 + 1] * g + cm[row * 5 + 2] * b) * 255f + cm[row * 5 + 4]
                near(expected[row] * 255f, v.coerceIn(0f, 255f), 1e-2f)
            }
            assertEquals(listOf(0f, 0f, 0f, 1f, 0f), cm.slice(15..19)) // alpha untouched
        }
    }

    @Test
    fun theAndroidRgbMatrixGivesTheSameColours() {
        for (look in looks) for ((r, g, b) in samples) {
            val expected = apply(look, r, g, b)
            val m = LookMath.rgbMatrix(LookMath.affine(look)) // column-major 4x4, multiplied with (r, g, b, 1)
            assertEquals(16, m.size)
            val input = floatArrayOf(r, g, b, 1f)
            for (row in 0..2) {
                var v = 0f
                for (col in 0..3) v += m[col * 4 + row] * input[col]
                near(expected[row], v.coerceIn(0f, 1f))
            }
            // alpha row: leaves alpha at 1
            var alpha = 0f
            for (col in 0..3) alpha += m[col * 4 + 3] * input[col]
            near(1f, alpha)
        }
    }

    @Test
    fun theLookupTableHoldsTheSameColoursOnItsGrid() {
        val look = Look(contrast = 0.5f, saturation = -0.3f, warmth = 0.4f, brightness = 0.1f)
        val size = 5
        val lines = LookMath.cube(look, size).trim().lines()
        assertEquals("LUT_3D_SIZE 5", lines[0])
        assertEquals(size * size * size, lines.size - 1)
        val a = LookMath.affine(look)
        // Red changes fastest, then green, then blue.
        for (idx in listOf(0, 1, 4, 5, 24, 25, 62, 124)) {
            val r = idx % size; val g = (idx / size) % size; val b = idx / (size * size)
            val expected = LookMath.apply(a, r / 4f, g / 4f, b / 4f)
            val got = lines[idx + 1].split(' ').map { it.toFloat() }
            for (c in 0..2) near(expected[c], got[c], 1e-5f)
        }
    }

    @Test
    fun presetsAreDistinctRealLooks() {
        val ids = LookPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(LookPresets.all.none { it.look.isNeutral })
        assertTrue(LookPresets.all.all { it.look == it.look.coerced() })
        assertEquals("Mono", LookPresets.byId("mono")?.label)
        assertEquals(null, LookPresets.byId("nope"))
        assertEquals(null, LookPresets.byId(null))
    }

    @Test
    fun aClipFromAnOlderBuildHasNoLook() = runTest {
        val store = InMemoryStore()
        store.files["p"] = """{"id":"p","name":"P","createdAt":1,"updatedAt":2,
            "clips":[{"id":"c","sourceUri":"content://x","sourceDurationUs":5000000,"trimEndUs":5000000}]}"""
        val clip = ProjectRepository(store).load("p")!!.clips.single()
        assertTrue(clip.look.isNeutral)
        assertEquals(null, clip.filterId)
    }

    @Test
    fun aLookSurvivesSavingAndLoading() = runTest {
        val repo = ProjectRepository(InMemoryStore())
        val look = Look(0.1f, -0.2f, 0.3f, -0.4f)
        val p = fullProject("p").let { it.copy(clips = it.clips.mapIndexed { i, c -> if (i == 0) c.copy(look = look, filterId = "warm") else c }) }
        repo.save(p)
        val c = repo.load("p")!!.clips[0]
        assertEquals(look, c.look)
        assertEquals("warm", c.filterId)
        assertTrue(repo.load("p")!!.clips[1].look.isNeutral)
    }
}
