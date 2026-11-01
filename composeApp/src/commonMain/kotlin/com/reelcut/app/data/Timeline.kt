package com.reelcut.app.data

/**
 * Pure timeline maths. Clip positions are never stored: each clip starts where the previous ends.
 * Everything here works on immutable lists so undo/redo is just keeping old lists.
 */
object Timeline {
    const val MIN_CLIP_US = 100_000L

    fun totalUs(clips: List<Clip>): Long = clips.sumOf { it.durationUs }

    /** Timeline start of each clip. */
    fun startsUs(clips: List<Clip>): LongArray {
        val out = LongArray(clips.size)
        var t = 0L
        clips.forEachIndexed { i, c ->
            out[i] = t
            t += c.durationUs
        }
        return out
    }

    data class Position(val index: Int, val offsetUs: Long)

    /** Which clip is at [timeUs], and how far into it. Times past the end land at the end of the last clip. */
    fun locate(clips: List<Clip>, timeUs: Long): Position? {
        if (clips.isEmpty()) return null
        var start = 0L
        val t = timeUs.coerceAtLeast(0L)
        clips.forEachIndexed { i, c ->
            val end = start + c.durationUs
            if (t < end || i == clips.lastIndex) {
                return Position(i, (t - start).coerceIn(0L, c.durationUs))
            }
            start = end
        }
        return null
    }

    /** Source-file time for a point [offsetUs] into the clip's timeline span. */
    fun sourceTimeUs(clip: Clip, offsetUs: Long): Long =
        clip.trimStartUs + (offsetUs * clip.effectiveSpeed).toLong()

    /** True when [timeUs] is far enough from a clip edge to split there. */
    fun canSplit(clips: List<Clip>, timeUs: Long): Boolean {
        val pos = locate(clips, timeUs) ?: return false
        val clip = clips[pos.index]
        val cut = sourceTimeUs(clip, pos.offsetUs)
        return cut - clip.trimStartUs >= MIN_CLIP_US && clip.trimEndUs - cut >= MIN_CLIP_US
    }

    /** Splits the clip under [timeUs] in two. Returns null if the cut is too close to an edge. */
    fun split(clips: List<Clip>, timeUs: Long, newId: String): List<Clip>? {
        if (!canSplit(clips, timeUs)) return null
        val pos = locate(clips, timeUs)!!
        val clip = clips[pos.index]
        val cut = sourceTimeUs(clip, pos.offsetUs)
        val first = clip.copy(trimEndUs = cut)
        val second = clip.copy(id = newId, trimStartUs = cut)
        return clips.toMutableList().apply {
            set(pos.index, first)
            add(pos.index + 1, second)
        }
    }

    /** Sets new trim points, clamped to the source and to the minimum clip length. */
    fun trim(clips: List<Clip>, index: Int, startUs: Long, endUs: Long): List<Clip> {
        if (index !in clips.indices) return clips
        val c = clips[index]
        val (s, e) = clampTrim(c.sourceDurationUs, startUs, endUs)
        return clips.toMutableList().apply { set(index, c.copy(trimStartUs = s, trimEndUs = e)) }
    }

    fun clampTrim(sourceDurationUs: Long, startUs: Long, endUs: Long): Pair<Long, Long> {
        val max = sourceDurationUs.coerceAtLeast(MIN_CLIP_US)
        val s = startUs.coerceIn(0L, max - MIN_CLIP_US)
        val e = endUs.coerceIn(s + MIN_CLIP_US, max)
        return s to e
    }

    fun delete(clips: List<Clip>, index: Int): List<Clip> =
        if (index in clips.indices) clips.toMutableList().apply { removeAt(index) } else clips

    fun duplicate(clips: List<Clip>, index: Int, newId: String): List<Clip> =
        if (index in clips.indices) {
            clips.toMutableList().apply { add(index + 1, clips[index].copy(id = newId)) }
        } else clips

    fun move(clips: List<Clip>, from: Int, to: Int): List<Clip> {
        if (from !in clips.indices) return clips
        val target = to.coerceIn(0, clips.lastIndex)
        if (from == target) return clips
        return clips.toMutableList().apply { add(target, removeAt(from)) }
    }

    /** Replaces clip [index] with [transform] applied to it. */
    fun update(clips: List<Clip>, index: Int, transform: (Clip) -> Clip): List<Clip> =
        if (index in clips.indices) clips.toMutableList().apply { set(index, transform(clips[index])) } else clips

    fun insert(clips: List<Clip>, at: Int, newClips: List<Clip>): List<Clip> =
        clips.toMutableList().apply { addAll(at.coerceIn(0, clips.size), newClips) }
}
