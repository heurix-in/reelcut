package com.reelcut.app.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.image.ImageOutput
import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.reelcut.app.R
import com.reelcut.app.data.Clip
import com.reelcut.app.engine.PreviewPlayer
import com.reelcut.app.data.Timeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays the edit as a playlist of trimmed clips and converts between player position and
 * timeline time. Speed and volume are applied per clip as the playlist moves on; framing
 * (canvas, rotation, fit/fill) is drawn by the Compose layer around the player view.
 * In trim mode it plays the whole source file of one clip instead.
 */
class AndroidPreviewPlayer(context: Context) : PreviewPlayer {

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
    }

    private var clips: List<Clip> = emptyList()
    private var starts: LongArray = LongArray(0)
    private var structure: List<Any> = emptyList()
    private var sourceMode = false

    /** Index of the clip the player is on, for the Compose framing. */
    private val _currentIndex = MutableStateFlow(0)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private var muted = false

    /** Last photo frame handed over by the player (photos are drawn by Compose). */
    private val _photoFrame = MutableStateFlow<Bitmap?>(null)
    val photoFrame: StateFlow<Bitmap?> = _photoFrame.asStateFlow()

    init {
        player.setImageOutput(object : ImageOutput {
            override fun onImageAvailable(presentationTimeUs: Long, bitmap: Bitmap) {
                _photoFrame.value = bitmap
            }

            override fun onDisabled() = Unit
        })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyClipParams()
            }
        })
    }

    private fun applyClipParams() {
        if (sourceMode) return
        val i = player.currentMediaItemIndex
        _currentIndex.value = i
        val clip = clips.getOrNull(i) ?: return
        player.playbackParameters = PlaybackParameters(clip.effectiveSpeed)
        player.volume = if (muted) 0f else clip.volume.coerceIn(0f, 1f)
    }

    /** What needs a playlist reload when it changes (speed, volume and framing do not). */
    private fun structureOf(list: List<Clip>): List<Any> =
        list.map { listOf(it.id, it.sourceUri, it.trimStartUs, it.trimEndUs, it.kind) }

    /** Loads [newClips] and keeps the playhead at [keepTimeUs]. Reloads only when the cut itself changed. */
    override fun setMuted(muted: Boolean) {
        if (this.muted == muted) return
        this.muted = muted
        applyClipParams()
    }

    override fun setClips(newClips: List<Clip>, keepTimeUs: Long, force: Boolean) {
        val newStructure = structureOf(newClips)
        val reload = force || sourceMode || newStructure != structure
        sourceMode = false
        clips = newClips
        starts = Timeline.startsUs(newClips)
        structure = newStructure
        if (!reload) {
            applyClipParams()
            return
        }
        val wasPlaying = player.playWhenReady
        if (newClips.isEmpty()) {
            player.clearMediaItems()
            return
        }
        val pos = Timeline.locate(newClips, keepTimeUs)!!
        player.setMediaItems(CompositionBuilder.previewItems(newClips), pos.index, mediaMs(newClips[pos.index], pos.offsetUs))
        player.prepare()
        player.playWhenReady = wasPlaying
        applyClipParams()
    }

    /** Timeline offset inside a clip to the player's position inside that item. */
    private fun mediaMs(clip: Clip, offsetUs: Long): Long = (offsetUs * clip.effectiveSpeed / 1000).toLong()

    /** Current timeline time in microseconds. */
    override fun positionUs(): Long {
        if (sourceMode || clips.isEmpty()) return 0L
        val i = player.currentMediaItemIndex.coerceIn(0, clips.lastIndex)
        val clip = clips[i]
        val inClip = (player.currentPosition * 1000 / clip.effectiveSpeed).toLong().coerceIn(0L, clip.durationUs)
        return starts[i] + inClip
    }

    override fun seekTo(timeUs: Long, exact: Boolean) {
        if (sourceMode) return
        val pos = Timeline.locate(clips, timeUs) ?: return
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(pos.index, mediaMs(clips[pos.index], pos.offsetUs))
    }

    override val isPlaying: Boolean
        get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) {
            if (sourceMode) player.seekTo(0) else player.seekTo(0, 0)
        }
        player.play()
    }

    override fun pause() = player.pause()

    // --- Trim mode: plays the full source of one clip ---

    override fun enterSource(clip: Clip, atUs: Long) {
        sourceMode = true
        player.pause()
        player.playbackParameters = PlaybackParameters(1f)
        player.volume = 1f
        player.setMediaItem(MediaItem.fromUri(Uri.parse(clip.sourceUri)), atUs / 1000)
        player.prepare()
    }

    override fun seekSource(timeUs: Long, exact: Boolean) {
        if (!sourceMode) return
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(timeUs / 1000)
    }

    override fun sourcePositionUs(): Long = if (sourceMode) player.currentPosition * 1000 else 0L

    /** Leaves trim mode and reloads the edit. */
    override fun exitSource(clips: List<Clip>, keepTimeUs: Long) {
        player.pause()
        setClips(clips, keepTimeUs, force = true)
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.preview_player, null) as PlayerView).apply {
                    setKeepContentOnPlayerReset(true)
                    this.player = this@AndroidPreviewPlayer.player
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
        )
    }

    override fun release() = player.release()
}
