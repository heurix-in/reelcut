package com.reelcut.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.reelcut.app.data.Clip
import com.reelcut.app.data.Timeline

/**
 * Plays the edit as a playlist of trimmed clips and converts between player position and
 * timeline time. In trim mode it plays the whole source file of one clip instead.
 */
class PreviewController(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
    }

    private var clips: List<Clip> = emptyList()
    private var starts: LongArray = LongArray(0)
    private var sourceMode = false

    /** Loads [newClips] and keeps the playhead at [keepTimeUs]. Does nothing if the clips did not change. */
    fun setClips(newClips: List<Clip>, keepTimeUs: Long, force: Boolean = false) {
        if (!force && !sourceMode && newClips == clips) return
        sourceMode = false
        clips = newClips
        starts = Timeline.startsUs(newClips)
        val wasPlaying = player.playWhenReady
        if (newClips.isEmpty()) {
            player.clearMediaItems()
            return
        }
        val pos = Timeline.locate(newClips, keepTimeUs)!!
        player.setMediaItems(CompositionBuilder.previewItems(newClips), pos.index, pos.offsetUs / 1000)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    /** Current timeline time in microseconds. */
    fun positionUs(): Long {
        if (sourceMode || clips.isEmpty()) return 0L
        val i = player.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
        val inClip = (player.currentPosition * 1000).coerceIn(0L, clips[i].durationUs)
        return starts[i] + inClip
    }

    fun seekTo(timeUs: Long, exact: Boolean) {
        if (sourceMode) return
        val pos = Timeline.locate(clips, timeUs) ?: return
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(pos.index, pos.offsetUs / 1000)
    }

    val isPlaying: Boolean get() = player.playWhenReady

    fun play() {
        if (player.playbackState == Player.STATE_ENDED) {
            if (sourceMode) player.seekTo(0) else player.seekTo(0, 0)
        }
        player.play()
    }

    fun pause() = player.pause()

    // --- Trim mode: plays the full source of one clip ---

    fun enterSource(clip: Clip, atUs: Long) {
        sourceMode = true
        player.pause()
        player.setMediaItem(MediaItem.fromUri(Uri.parse(clip.sourceUri)), atUs / 1000)
        player.prepare()
    }

    fun seekSource(timeUs: Long, exact: Boolean) {
        if (!sourceMode) return
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(timeUs / 1000)
    }

    fun sourcePositionUs(): Long = if (sourceMode) player.currentPosition * 1000 else 0L

    /** Leaves trim mode and reloads the edit. */
    fun exitSource(newClips: List<Clip>, keepTimeUs: Long) {
        player.pause()
        setClips(newClips, keepTimeUs, force = true)
    }

    fun release() = player.release()
}
