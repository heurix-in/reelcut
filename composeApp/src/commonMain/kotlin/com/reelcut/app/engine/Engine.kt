package com.reelcut.app.engine

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.Clip
import com.reelcut.app.data.OverlayAnim
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Project
import com.reelcut.app.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything that differs between Android and Windows lives behind these interfaces.
 * Android uses Media3; Windows uses FFmpeg. Screens, timeline maths and projects are shared.
 *
 * Media files are referred to by a string: a content:// URI on Android, a file path on Windows.
 */
interface MediaEngine {
    val thumbnails: Thumbnails

    /** Turns picked files into clips. Unreadable files are skipped. */
    suspend fun importClips(refs: List<String>): List<Clip>

    /** Turns picked sound files into audio clips (placed at 0:00; the caller moves them). Unreadable files are skipped. */
    suspend fun importAudio(refs: List<String>): List<AudioClip>

    /**
     * A blank frame in one colour, shown like a photo ([Clip.blank]). [width] x [height] is the
     * shape it reports to the canvas (the picture file itself is tiny).
     */
    suspend fun createBlankClip(argb: Long, width: Int, height: Int): Clip?

    /** A new microphone recorder for voice-overs. */
    fun newRecorder(): VoiceRecorder

    /** True if the source file can still be opened. */
    fun isReadable(ref: String): Boolean

    fun newPreview(): PreviewPlayer

    /**
     * Renders the project and saves it where the platform keeps videos. [layers] are the text and
     * logo overlays, already drawn at the output size. Throws [ExportFailed].
     */
    suspend fun export(
        project: Project,
        settings: ExportSettings,
        layers: List<OverlayLayer>,
        onProgress: (Int) -> Unit,
    ): String

    /** Loads an image (logo) at most [maxSide] pixels on its long side, keeping transparency. */
    suspend fun loadImage(ref: String, maxSide: Int): ImageBitmap?

    /** Where exports are saved, in words ("Movies/Reelcut"). */
    val exportFolderLabel: String

    /** "Share" on a phone, "Show in folder" on a PC. */
    val shareLabel: String

    suspend fun listExports(): List<ExportItem>
    suspend fun exportThumbnail(item: ExportItem): ImageBitmap?
    fun openVideo(ref: String)
    fun shareVideo(ref: String)
}

/** Records the microphone to a file the project keeps. One recorder per recording. */
interface VoiceRecorder {
    /** Starts recording. False if the microphone could not be used. */
    fun start(): Boolean

    /** How long it has been recording. */
    val elapsedUs: Long

    /** Stops and returns the saved recording named [name], or null if it was too short or failed. */
    suspend fun stop(name: String): AudioClip?

    /** Stops and throws the recording away. */
    fun cancel()
}

/**
 * A full-frame transparent image shown between [startUs] and [endUs] of the timeline. If it is animated
 * the engine moves it as [com.reelcut.app.data.AnimMath] says; [centreX] and [centreY] (fractions of the
 * frame) are the point it grows and shrinks about.
 */
class OverlayLayer(
    val image: ImageBitmap,
    val startUs: Long,
    val endUs: Long,
    val animIn: OverlayAnim = OverlayAnim.NONE,
    val animOut: OverlayAnim = OverlayAnim.NONE,
    val centreX: Float = 0.5f,
    val centreY: Float = 0.5f,
) {
    val isAnimated: Boolean get() = animIn != OverlayAnim.NONE || animOut != OverlayAnim.NONE
}

class ExportFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ExportItem(val ref: String, val name: String, val durationMs: Long)

interface Thumbnails {
    /** Rounds a time so nearby requests share one frame. */
    fun quantize(timeUs: Long): Long
    fun peek(ref: String, timeUs: Long, heightPx: Int): ImageBitmap?
    suspend fun get(ref: String, timeUs: Long, heightPx: Int): ImageBitmap?
}

/**
 * Plays the edit (a playlist of trimmed clips) and converts between player position and timeline
 * time. In "source" mode (trim) it plays the whole file of one clip instead.
 */
interface PreviewPlayer {
    /** Index of the clip on screen. */
    val currentIndex: StateFlow<Int>

    /** True while playing and not at the end. */
    val isPlaying: Boolean

    fun setClips(clips: List<Clip>, keepTimeUs: Long, force: Boolean = false)
    fun setMuted(muted: Boolean)

    /** The music and voice-overs heard along with the video (already cut to the video's length). */
    fun setAudio(clips: List<AudioClip>)
    fun positionUs(): Long
    fun seekTo(timeUs: Long, exact: Boolean)
    fun play()
    fun pause()

    fun enterSource(clip: Clip, atUs: Long)
    fun seekSource(timeUs: Long, exact: Boolean)
    fun sourcePositionUs(): Long
    fun exitSource(clips: List<Clip>, keepTimeUs: Long)

    /**
     * Draws the current video frame, stretched to [modifier]'s bounds (framing is done by the caller).
     * [colorFilter] is the clip's colour look, or null for none.
     */
    @Composable
    fun Surface(modifier: Modifier, colorFilter: ColorFilter? = null)

    fun release()
}

/** The shared services, created once by each platform at start-up. */
class Services(
    val repository: ProjectRepository,
    val engine: MediaEngine,
    /** For work that must outlive a screen, like the final save when leaving the editor. */
    val appScope: CoroutineScope,
) {
    companion object {
        lateinit var current: Services
    }
}
