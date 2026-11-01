package com.reelcut.app.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class TimelineTest {
    private fun clip(id: String, start: Long, end: Long, source: Long = 10_000_000L) =
        Clip(id = id, sourceUri = "content://$id", sourceDurationUs = source, trimStartUs = start, trimEndUs = end)

    private val a = clip("a", 0, 4_000_000)          // 4 s
    private val b = clip("b", 1_000_000, 3_000_000)  // 2 s
    private val c = clip("c", 0, 6_000_000)          // 6 s
    private val clips = listOf(a, b, c)

    @Test fun totalAndStarts() {
        assertEquals(12_000_000L, Timeline.totalUs(clips))
        assertEquals(listOf(0L, 4_000_000L, 6_000_000L), Timeline.startsUs(clips).toList())
    }

    @Test fun locateInsideAndAtEdges() {
        assertEquals(Timeline.Position(0, 0), Timeline.locate(clips, 0))
        assertEquals(Timeline.Position(1, 0), Timeline.locate(clips, 4_000_000))
        assertEquals(Timeline.Position(1, 500_000), Timeline.locate(clips, 4_500_000))
        assertEquals(Timeline.Position(2, 6_000_000), Timeline.locate(clips, 99_000_000))
        assertNull(Timeline.locate(emptyList(), 0))
    }

    @Test fun splitKeepsTotalDuration() {
        val out = Timeline.split(clips, 5_000_000, "b2")!!
        assertEquals(4, out.size)
        assertEquals(1_000_000L, out[1].trimStartUs)
        assertEquals(2_000_000L, out[1].trimEndUs)
        assertEquals(2_000_000L, out[2].trimStartUs)
        assertEquals("b2", out[2].id)
        assertEquals(Timeline.totalUs(clips), Timeline.totalUs(out))
    }

    @Test fun splitRefusedNearEdge() {
        assertNull(Timeline.split(clips, 4_050_000, "x"))
        assertFalse(Timeline.canSplit(clips, 3_950_000))
        assertTrue(Timeline.canSplit(clips, 3_800_000))
    }

    @Test fun trimClamps() {
        val out = Timeline.trim(clips, 0, -5, 50)
        assertEquals(0L, out[0].trimStartUs)
        assertEquals(Timeline.MIN_CLIP_US, out[0].trimEndUs)
        val out2 = Timeline.trim(clips, 2, 9_990_000, 20_000_000)
        assertEquals(10_000_000L - Timeline.MIN_CLIP_US, out2[2].trimStartUs)
        assertEquals(10_000_000L, out2[2].trimEndUs)
    }

    @Test fun deleteDuplicateMove() {
        assertEquals(listOf("a", "c"), Timeline.delete(clips, 1).map { it.id })
        assertEquals(listOf("a", "a2", "b", "c"), Timeline.duplicate(clips, 0, "a2").map { it.id })
        assertEquals(listOf("b", "c", "a"), Timeline.move(clips, 0, 2).map { it.id })
        assertEquals(listOf("c", "a", "b"), Timeline.move(clips, 2, 0).map { it.id })
        assertEquals(clips, Timeline.move(clips, 1, 1))
    }

    @Test fun insertAtPosition() {
        val x = clip("x", 0, 1_000_000)
        assertEquals(listOf("a", "x", "b", "c"), Timeline.insert(clips, 1, listOf(x)).map { it.id })
        assertEquals(listOf("a", "b", "c", "x"), Timeline.insert(clips, 99, listOf(x)).map { it.id })
    }
}
