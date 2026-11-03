package com.reelcut.app.data

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimMathTest {
    private val start = 1_000_000L
    private val end = 5_000_000L // 4 s long, so each animation lasts the full 0.5 s

    private fun near(expected: Float, actual: Float, eps: Float = 1e-4f) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    private fun at(i: OverlayAnim, o: OverlayAnim, t: Long) = AnimMath.state(i, o, start, end, t)

    @Test
    fun noAnimationIsAlwaysAtRest() {
        for (t in listOf(start, 2_000_000L, end - 1)) assertEquals(AnimState.Rest, at(OverlayAnim.NONE, OverlayAnim.NONE, t))
    }

    @Test
    fun fadeRampsLinearlyInAndOut() {
        near(0f, at(OverlayAnim.FADE, OverlayAnim.FADE, start).alpha)
        near(0.5f, at(OverlayAnim.FADE, OverlayAnim.FADE, start + 250_000).alpha)
        near(1f, at(OverlayAnim.FADE, OverlayAnim.FADE, start + 500_000).alpha)
        near(1f, at(OverlayAnim.FADE, OverlayAnim.FADE, 3_000_000).alpha)
        near(0.5f, at(OverlayAnim.FADE, OverlayAnim.FADE, end - 250_000).alpha)
        near(0f, at(OverlayAnim.FADE, OverlayAnim.FADE, end).alpha)
        // A fade does not move or resize anything.
        val s = at(OverlayAnim.FADE, OverlayAnim.FADE, start + 100_000)
        assertEquals(0f, s.dx); assertEquals(0f, s.dy); assertEquals(1f, s.scale)
    }

    @Test
    fun onlyTheEndThatIsAnimatedChanges() {
        near(1f, at(OverlayAnim.NONE, OverlayAnim.FADE, start).alpha)
        near(1f, at(OverlayAnim.FADE, OverlayAnim.NONE, end).alpha)
    }

    @Test
    fun slidingInMovesInTheNamedDirectionAndSettlesAtRest() {
        val up = at(OverlayAnim.SLIDE_UP, OverlayAnim.NONE, start)
        near(0f, up.dx); near(AnimMath.SLIDE_DISTANCE, up.dy) // starts below, moves up
        val down = at(OverlayAnim.SLIDE_DOWN, OverlayAnim.NONE, start)
        near(-AnimMath.SLIDE_DISTANCE, down.dy)
        near(AnimMath.SLIDE_DISTANCE, at(OverlayAnim.SLIDE_LEFT, OverlayAnim.NONE, start).dx) // comes from the right, moving left
        val settled = at(OverlayAnim.SLIDE_UP, OverlayAnim.NONE, start + 500_000)
        near(0f, settled.dy); near(1f, settled.alpha)
        // Easing out: past half way in time it has covered more than half the distance.
        assertTrue(at(OverlayAnim.SLIDE_UP, OverlayAnim.NONE, start + 250_000).dy < AnimMath.SLIDE_DISTANCE / 2)
    }

    @Test
    fun slidingOutMovesInTheNamedDirectionAndEndsAway() {
        near(0f, at(OverlayAnim.NONE, OverlayAnim.SLIDE_UP, end - 500_000).dy)
        val gone = at(OverlayAnim.NONE, OverlayAnim.SLIDE_UP, end)
        near(-AnimMath.SLIDE_DISTANCE, gone.dy); near(0f, gone.alpha)
        near(AnimMath.SLIDE_DISTANCE, at(OverlayAnim.NONE, OverlayAnim.SLIDE_RIGHT, end).dx)
    }

    @Test
    fun popGrowsInAndShrinksOutAboutItsCentre() {
        near(AnimMath.POP_FROM, at(OverlayAnim.POP, OverlayAnim.POP, start).scale)
        near(1f, at(OverlayAnim.POP, OverlayAnim.POP, start + 500_000).scale)
        near(1f, at(OverlayAnim.POP, OverlayAnim.POP, 3_000_000).scale)
        near(AnimMath.POP_FROM, at(OverlayAnim.POP, OverlayAnim.POP, end).scale)
        val mid = at(OverlayAnim.POP, OverlayAnim.POP, start + 250_000)
        assertTrue(mid.scale > AnimMath.POP_FROM && mid.scale < 1f)
        assertEquals(0f, mid.dx); assertEquals(0f, mid.dy)
    }

    @Test
    fun aShortOverlayGivesEachAnimationHalfItsTime() {
        assertEquals(500_000L, AnimMath.durationUs(0, 4_000_000))
        assertEquals(500_000L, AnimMath.durationUs(0, 1_000_000))
        assertEquals(200_000L, AnimMath.durationUs(0, 400_000))
        // 0.4 s long: fully faded in exactly half way, then straight into fading out.
        near(1f, AnimMath.state(OverlayAnim.FADE, OverlayAnim.FADE, 0, 400_000, 200_000).alpha)
        near(0f, AnimMath.state(OverlayAnim.FADE, OverlayAnim.FADE, 0, 400_000, 0).alpha)
        near(0f, AnimMath.state(OverlayAnim.FADE, OverlayAnim.FADE, 0, 400_000, 400_000).alpha)
    }

    @Test
    fun theSameOverlayAnimatesTheSameWayThroughTheOverlayHelper() {
        val o = Overlay(id = "t", kind = OverlayKind.TEXT, startUs = start, endUs = end, animIn = OverlayAnim.POP, animOut = OverlayAnim.FADE)
        assertEquals(AnimMath.state(OverlayAnim.POP, OverlayAnim.FADE, start, end, 1_100_000), AnimMath.state(o, 1_100_000))
    }

    @Test
    fun animationSettingsSurviveSavingAndOldOverlaysHaveNone() = runTest {
        val repo = ProjectRepository(InMemoryStore().also {
            it.files["old"] = """{"id":"old","name":"O","createdAt":1,"updatedAt":2,
                "overlays":[{"id":"t","kind":"TEXT","startUs":0,"endUs":1000000,"text":"Hi"}]}"""
            it.files["bad"] = """{"id":"bad","name":"B","createdAt":1,"updatedAt":2,
                "overlays":[{"id":"t","kind":"TEXT","startUs":0,"endUs":1000000,"text":"Hi","animIn":"SPIN"}]}"""
        })
        val old = repo.load("old")!!.overlays.single()
        assertEquals(OverlayAnim.NONE, old.animIn); assertEquals(OverlayAnim.NONE, old.animOut)
        assertEquals(OverlayAnim.NONE, repo.load("bad")!!.overlays.single().animIn) // unknown name falls back
        val saved = fullProject("p").let { it.copy(overlays = it.overlays.map { o -> o.copy(animIn = OverlayAnim.SLIDE_LEFT, animOut = OverlayAnim.POP) }) }
        repo.save(saved)
        assertEquals(OverlayAnim.SLIDE_LEFT, repo.load("p")!!.overlays[0].animIn)
        assertEquals(OverlayAnim.POP, repo.load("p")!!.overlays[0].animOut)
    }
}
