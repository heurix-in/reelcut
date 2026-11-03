package com.reelcut.app.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Project
import com.reelcut.app.engine.ExportItem
import com.reelcut.app.engine.MediaEngine
import com.reelcut.app.engine.OverlayLayer
import com.reelcut.app.engine.PreviewPlayer
import com.reelcut.app.engine.Thumbnails
import com.reelcut.app.engine.VoiceRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A preview player that only remembers what it was told. */
class FakePreview : PreviewPlayer {
    override val currentIndex = MutableStateFlow(0)
    override var isPlaying = false

    var clips: List<Clip> = emptyList()
    var setClipsCalls = 0
    var lastForce = false
    var mutedNow = false
    var audioNow: List<AudioClip> = emptyList()
    var positionUs = 0L
    var lastSeekUs = -1L
    var sourceClip: Clip? = null
    var sourcePositionUs = 0L
    var exitedWith: List<Clip>? = null
    var released = false

    override fun setClips(clips: List<Clip>, keepTimeUs: Long, force: Boolean) {
        this.clips = clips
        setClipsCalls++
        lastForce = force
    }

    override fun setMuted(muted: Boolean) {
        mutedNow = muted
    }

    override fun setAudio(clips: List<AudioClip>) {
        audioNow = clips
    }

    override fun positionUs(): Long = positionUs

    override fun seekTo(timeUs: Long, exact: Boolean) {
        lastSeekUs = timeUs
        positionUs = timeUs
    }

    override fun play() {
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun enterSource(clip: Clip, atUs: Long) {
        sourceClip = clip
        sourcePositionUs = atUs
    }

    override fun seekSource(timeUs: Long, exact: Boolean) {
        sourcePositionUs = timeUs
    }

    override fun sourcePositionUs(): Long = sourcePositionUs

    override fun exitSource(clips: List<Clip>, keepTimeUs: Long) {
        exitedWith = clips
        sourceClip = null
    }

    @Composable
    override fun Surface(modifier: Modifier) {
    }

    override fun release() {
        released = true
    }
}

class FakeRecorder : VoiceRecorder {
    var startOk = true
    var started = false
    var cancelled = false
    var stoppedWithName: String? = null
    var result: AudioClip? = null
    override var elapsedUs = 0L

    override fun start(): Boolean {
        started = startOk
        return startOk
    }

    override suspend fun stop(name: String): AudioClip? {
        stoppedWithName = name
        return result
    }

    override fun cancel() {
        cancelled = true
    }
}

object FakeThumbnails : Thumbnails {
    override fun quantize(timeUs: Long): Long = timeUs
    override fun peek(ref: String, timeUs: Long, heightPx: Int): ImageBitmap? = null
    override suspend fun get(ref: String, timeUs: Long, heightPx: Int): ImageBitmap? = null
}

/** A media engine whose answers the test chooses. */
class FakeEngine : MediaEngine {
    override val thumbnails: Thumbnails = FakeThumbnails
    val preview = FakePreview()
    val recorder = FakeRecorder()

    var importClipsResult: List<Clip> = emptyList()
    var importAudioResult: List<AudioClip> = emptyList()
    var blankClip: Clip? = null
    val unreadable = mutableSetOf<String>()

    // Export: the test can hold it open, make it fail, and see what it was given.
    var exportCalls = 0
    var exportedProject: Project? = null
    var exportedSettings: ExportSettings? = null
    var exportError: Exception? = null
    var exportGate: CompletableDeferred<Unit>? = null
    val exportStarted = CompletableDeferred<Unit>()
    val exportCancelled = CompletableDeferred<Unit>()
    var exportRef = "Movies/Reelcut/out.mp4"

    override suspend fun importClips(refs: List<String>): List<Clip> = importClipsResult
    override suspend fun importAudio(refs: List<String>): List<AudioClip> = importAudioResult
    override suspend fun createBlankClip(argb: Long, width: Int, height: Int): Clip? = blankClip
    override fun newRecorder(): VoiceRecorder = recorder
    override fun isReadable(ref: String): Boolean = ref !in unreadable
    override fun newPreview(): PreviewPlayer = preview

    override suspend fun export(
        project: Project,
        settings: ExportSettings,
        layers: List<OverlayLayer>,
        onProgress: (Int) -> Unit,
    ): String {
        exportCalls++
        exportedProject = project
        exportedSettings = settings
        exportStarted.complete(Unit)
        try {
            onProgress(10)
            exportGate?.await()
            exportError?.let { throw it }
            onProgress(100)
            return exportRef
        } catch (e: kotlinx.coroutines.CancellationException) {
            exportCancelled.complete(Unit)
            throw e
        }
    }

    override suspend fun loadImage(ref: String, maxSide: Int): ImageBitmap? = null
    override val exportFolderLabel: String = "Movies/Reelcut"
    override val shareLabel: String = "Share"
    override suspend fun listExports(): List<ExportItem> = emptyList()
    override suspend fun exportThumbnail(item: ExportItem): ImageBitmap? = null
    override fun openVideo(ref: String) {}
    override fun shareVideo(ref: String) {}
}
