package com.reelcut.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class AudioKind { MUSIC, VOICE }

/**
 * A sound laid over the video: music from a file or a recorded voice-over. All times are in
 * microseconds. [startUs] is where it begins on the timeline; the part of the file that plays is
 * [trimStartUs] until [trimEndUs] (exclusive), at normal speed.
 */
@Serializable
data class AudioClip(
    val id: String,
    val kind: AudioKind = AudioKind.MUSIC,
    val name: String = "",
    val sourceUri: String,
    val sourceDurationUs: Long,
    val trimStartUs: Long = 0,
    val trimEndUs: Long,
    /** Timeline position of the first sample. */
    val startUs: Long = 0,
    /** 0 (silent) to 2.0 (200%). */
    val volume: Float = 1f,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
) {
    val durationUs: Long get() = (trimEndUs - trimStartUs).coerceAtLeast(0L)
    val endUs: Long get() = startUs + durationUs
    val isVoice: Boolean get() = kind == AudioKind.VOICE

    companion object {
        const val MAX_VOLUME = 2f
        const val MIN_US = 100_000L
        const val MAX_FADE_US = 10_000_000L
    }
}

object AudioMath {

    /** Puts each clip on the first lane where it doesn't overlap another. Returns lane per clip id. */
    fun lanes(clips: List<AudioClip>): Map<String, Int> {
        val laneEnds = mutableListOf<Long>()
        val out = HashMap<String, Int>()
        clips.sortedWith(compareBy({ it.startUs }, { it.id })).forEach { c ->
            val lane = laneEnds.indexOfFirst { it <= c.startUs }.let { if (it < 0) laneEnds.size else it }
            if (lane == laneEnds.size) laneEnds += c.endUs else laneEnds[lane] = c.endUs
            out[c.id] = lane
        }
        return out
    }

    /**
     * Loudness multiplier of [clip] at timeline time [timeUs]: volume times the fade-in/fade-out
     * ramps (linear). Zero outside the clip.
     */
    fun gain(clip: AudioClip, timeUs: Long): Float {
        if (timeUs < clip.startUs || timeUs >= clip.endUs) return 0f
        val into = timeUs - clip.startUs
        val left = clip.endUs - timeUs
        var g = clip.volume
        if (clip.fadeInUs > 0 && into < clip.fadeInUs) g *= into.toFloat() / clip.fadeInUs
        if (clip.fadeOutUs > 0 && left < clip.fadeOutUs) g *= left.toFloat() / clip.fadeOutUs
        return g.coerceAtLeast(0f)
    }

    /** Keeps both fades non-negative, at most [AudioClip.MAX_FADE_US], and together no longer than the clip. */
    fun clampFades(clip: AudioClip): AudioClip {
        val dur = clip.durationUs
        val fi = clip.fadeInUs.coerceIn(0L, minOf(AudioClip.MAX_FADE_US, dur))
        val fo = clip.fadeOutUs.coerceIn(0L, minOf(AudioClip.MAX_FADE_US, (dur - fi).coerceAtLeast(0L)))
        return if (fi == clip.fadeInUs && fo == clip.fadeOutUs) clip else clip.copy(fadeInUs = fi, fadeOutUs = fo)
    }

    /**
     * What is actually heard when the video is [totalUs] long: clips that start after the end are
     * dropped, clips running past it are cut at the end (the fade-out then lands on the end), and
     * clips that are nearly silent or too short are left out.
     */
    fun fit(clips: List<AudioClip>, totalUs: Long): List<AudioClip> = clips.mapNotNull { c ->
        if (c.volume <= 0f || c.startUs >= totalUs) return@mapNotNull null
        val cut = if (c.endUs > totalUs) c.copy(trimEndUs = c.trimStartUs + (totalUs - c.startUs)) else c
        if (cut.durationUs < AudioClip.MIN_US) null else clampFades(cut)
    }

    /** Where a new clip goes: at the playhead, but never so late that nothing of it would be heard. */
    fun placeAt(playheadUs: Long, totalUs: Long): Long =
        playheadUs.coerceIn(0L, (totalUs - AudioClip.MIN_US).coerceAtLeast(0L))

    /** Re-validates a clip after an edit: volume range, trim inside the file, start inside the video. */
    fun normalise(clip: AudioClip, totalUs: Long): AudioClip {
        val ts = clip.trimStartUs.coerceIn(0L, (clip.sourceDurationUs - AudioClip.MIN_US).coerceAtLeast(0L))
        val te = clip.trimEndUs.coerceIn(minOf(ts + AudioClip.MIN_US, clip.sourceDurationUs), clip.sourceDurationUs)
        val start = clip.startUs.coerceIn(0L, (totalUs - AudioClip.MIN_US).coerceAtLeast(0L))
        return clampFades(
            clip.copy(
                trimStartUs = ts,
                trimEndUs = te,
                startUs = start,
                volume = clip.volume.coerceIn(0f, AudioClip.MAX_VOLUME),
            )
        )
    }
}
