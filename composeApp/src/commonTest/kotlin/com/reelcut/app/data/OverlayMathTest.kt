package com.reelcut.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayMathTest {
    private fun o(id: String, s: Long, e: Long) = Overlay(id = id, kind = OverlayKind.TEXT, startUs = s, endUs = e)

    @Test fun lanesStackOnlyWhenOverlapping() {
        val lanes = OverlayMath.lanes(listOf(o("a", 0, 3), o("b", 1, 4), o("c", 3, 5), o("d", 4, 6)))
        assertEquals(0, lanes["a"])
        assertEquals(1, lanes["b"])
        assertEquals(0, lanes["c"])
        assertEquals(1, lanes["d"])
    }

    @Test fun newRangeStaysInsideVideo() {
        assertEquals(2_000_000L to 5_000_000L, OverlayMath.rangeAt(2_000_000, 10_000_000, 3_000_000))
        assertEquals(7_000_000L to 10_000_000L, OverlayMath.rangeAt(9_500_000, 10_000_000, 3_000_000))
        assertEquals(0L to 2_000_000L, OverlayMath.rangeAt(1_000_000, 2_000_000, 3_000_000))
    }

    @Test fun clampKeepsMinimumLength() {
        assertEquals(1_000_000L to 1_100_000L, OverlayMath.clampRange(1_000_000, 900_000, 10_000_000))
        assertEquals(9_900_000L to 10_000_000L, OverlayMath.clampRange(12_000_000, 13_000_000, 10_000_000))
    }

    @Test fun visibility() {
        val x = o("x", 1_000_000, 2_000_000)
        assertTrue(x.visibleAt(1_000_000))
        assertFalse(x.visibleAt(2_000_000))
    }
}
