package com.reelcut.app.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioMath
import kotlin.math.abs

/**
 * Plays the music and voice-overs while the video preview plays. Each sound has its own player;
 * every 40 ms they are lined up with the video's timeline time (re-seeking only if one drifts).
 * Volume above 100% is only heard in the exported video.
 */
class AndroidAudioPreview(
    private val context: Context,
    /** Current timeline time in microseconds. */
    private val timeUs: () -> Long,
    /** True while the edit is playing (not in trim mode). */
    private val isPlaying: () -> Boolean,
) {
    private class Voice(var clip: AudioClip, val player: ExoPlayer)

    private val voices = LinkedHashMap<String, Voice>()
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var released = false

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            sync()
            if (voices.isNotEmpty()) handler.postDelayed(this, 40) else ticking = false
        }
    }

    fun setClips(clips: List<AudioClip>) {
        if (released) return
        val ids = clips.map { it.id }.toSet()
        voices.keys.filter { it !in ids }.forEach { id -> voices.remove(id)?.player?.release() }
        clips.forEach { c ->
            val existing = voices[c.id]
            if (existing == null) {
                voices[c.id] = Voice(c, newPlayer(c))
            } else {
                if (existing.clip.sourceUri != c.sourceUri) {
                    existing.player.setMediaItem(MediaItem.fromUri(Uri.parse(c.sourceUri)))
                    existing.player.prepare()
                }
                existing.clip = c
            }
        }
        if (voices.isNotEmpty() && !ticking) {
            ticking = true
            handler.post(tick)
        }
    }

    private fun newPlayer(c: AudioClip): ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        setSeekParameters(SeekParameters.CLOSEST_SYNC)
        setMediaItem(MediaItem.fromUri(Uri.parse(c.sourceUri)))
        prepare()
    }

    /** Pauses everything (trim mode, or leaving the editor). */
    fun pauseAll() {
        voices.values.forEach { it.player.playWhenReady = false }
    }

    private fun sync() {
        val playing = isPlaying()
        val t = timeUs()
        voices.values.forEach { v ->
            val c = v.clip
            val p = v.player
            val inside = t >= c.startUs && t < c.endUs
            if (!playing || !inside) {
                if (p.playWhenReady) p.playWhenReady = false
                return@forEach
            }
            if (p.playbackState == Player.STATE_ENDED) return@forEach
            val want = c.trimStartUs + (t - c.startUs)
            val have = p.currentPosition * 1000
            if (!p.playWhenReady || abs(have - want) > DRIFT_US) {
                p.seekTo(want / 1000)
            }
            if (!p.playWhenReady) p.playWhenReady = true
            p.volume = AudioMath.gain(c, t).coerceIn(0f, 1f)
        }
    }

    fun release() {
        released = true
        handler.removeCallbacks(tick)
        voices.values.forEach { it.player.release() }
        voices.clear()
    }

    private companion object {
        const val DRIFT_US = 250_000L
    }
}
