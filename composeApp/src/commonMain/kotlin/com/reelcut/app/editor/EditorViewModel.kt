package com.reelcut.app.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioMath
import com.reelcut.app.data.Canvas
import com.reelcut.app.data.CanvasMath
import com.reelcut.app.data.Overlay
import com.reelcut.app.data.OverlayKind
import com.reelcut.app.data.OverlayMath
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Project
import com.reelcut.app.data.ProjectRepository
import com.reelcut.app.data.Timeline
import com.reelcut.app.engine.ExportFailed
import com.reelcut.app.engine.OverlayLayer
import com.reelcut.app.engine.Services
import com.reelcut.app.engine.VoiceRecorder
import com.reelcut.app.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int) : ExportState
    data class Done(val ref: String) : ExportState
    data class Failed(val message: String) : ExportState
}

enum class Mode { Edit, Trim, Reorder, Speed, Volume, Transform, Canvas, Duration, Text, Logo, Audio }

/**
 * Holds the open project and every edit made to it. Each edit makes a new immutable Project;
 * the previous one goes on the undo stack. The project is saved 500 ms after the last edit.
 */
class EditorViewModel(private val services: Services, private val projectId: String) : ViewModel() {

    private val repo = services.repository
    private val engine = services.engine
    val thumbnails = engine.thumbnails
    val preview = engine.newPreview()

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** Clips whose source file can no longer be opened. */
    private val _missing = MutableStateFlow<Set<String>>(emptySet())
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** The text or logo being edited. */
    private val _selectedOverlayId = MutableStateFlow<String?>(null)
    val selectedOverlayId: StateFlow<String?> = _selectedOverlayId.asStateFlow()
    private var overlayIsNew = false
    val isNewOverlay: Boolean get() = overlayIsNew

    /** The music or voice-over being edited in the Audio panel. */
    private val _selectedAudioId = MutableStateFlow<String?>(null)
    val selectedAudioId: StateFlow<String?> = _selectedAudioId.asStateFlow()

    /** Voice-over in progress: how long it has been recording, or null. */
    private val _recordingUs = MutableStateFlow<Long?>(null)
    val recordingUs: StateFlow<Long?> = _recordingUs.asStateFlow()
    private var recorder: VoiceRecorder? = null
    private var recordStartUs = 0L

    private val _mode = MutableStateFlow(Mode.Edit)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /** Timeline time of the playhead, in microseconds. */
    private val _playheadUs = MutableStateFlow(0L)
    val playheadUs: StateFlow<Long> = _playheadUs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private var saveJob: Job? = null
    private var exportJob: Job? = null
    private var tickJob: Job? = null

    init {
        viewModelScope.launch {
            val p = repo.load(projectId)
            if (p == null) {
                _loadFailed.value = true
                return@launch
            }
            _missing.value = withContext(Dispatchers.Default) {
                p.clips.filterNot { engine.isReadable(it.sourceUri) }.map { it.id }.toSet()
            }
            _project.value = p
            syncPreviewAudio(p)
            if (_missing.value.isEmpty()) preview.setClips(p.clips, 0L)
            refreshUndoFlags()
        }
        tickJob = viewModelScope.launch {
            while (true) {
                val playing = preview.isPlaying
                if (playing && _mode.value != Mode.Trim) {
                    _playheadUs.value = preview.positionUs()
                }
                _isPlaying.value = playing
                recorder?.let { r ->
                    _recordingUs.value = r.elapsedUs
                    // The video reached its end: that ends the voice-over too.
                    if (!playing && r.elapsedUs > 600_000L) stopRecording()
                }
                delay(33)
            }
        }
    }

    // ---------- Editing ----------

    private fun commit(transform: (Project) -> Project) {
        val current = _project.value ?: return
        val next = transform(current)
        if (next == current) return
        undoStack.addLast(current)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        apply(next.copy(updatedAt = nowMillis()))
    }

    private fun apply(next: Project) {
        _project.value = next
        val total = next.durationUs
        if (_playheadUs.value > total) _playheadUs.value = total
        if (_selectedId.value != null && next.clips.none { it.id == _selectedId.value }) _selectedId.value = null
        syncPreviewAudio(next)
        if (_mode.value != Mode.Trim && _missing.value.isEmpty()) preview.setClips(next.clips, _playheadUs.value)
        refreshUndoFlags()
        scheduleSave()
    }

    /** Tells the preview which sounds to play: none of them while a voice-over is being recorded. */
    private fun syncPreviewAudio(p: Project) {
        val recording = recorder != null
        preview.setMuted(p.muteOriginal || recording)
        preview.setAudio(if (recording) emptyList() else AudioMath.fit(p.audio, p.durationUs))
    }

    private fun refreshUndoFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun undo() {
        val current = _project.value ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        apply(prev)
    }

    fun redo() {
        val current = _project.value ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        apply(next)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            _project.value?.let { repo.save(it) }
        }
    }

    fun rename(name: String) = commit { it.copy(name = name) }

    fun toggleMuteOriginal() = commit { it.copy(muteOriginal = !it.muteOriginal) }

    /** The clip tools act on: the selected one, or the one under the playhead. */
    fun targetIndex(): Int {
        val p = _project.value ?: return -1
        _selectedId.value?.let { id -> p.clips.indexOfFirst { it.id == id }.let { if (it >= 0) return it } }
        return Timeline.locate(p.clips, _playheadUs.value)?.index ?: -1
    }

    /** Tapping the selected clip again deselects it. */
    fun select(id: String?) {
        _selectedId.value = if (_selectedId.value == id) null else id
    }

    fun deselect() {
        _selectedId.value = null
    }

    /** Where new clips go: at the clip edge nearest the playhead. */
    fun insertIndexAtPlayhead(): Int {
        val p = _project.value ?: return 0
        val pos = Timeline.locate(p.clips, _playheadUs.value) ?: return p.clips.size
        val clip = p.clips[pos.index]
        return if (pos.offsetUs < clip.durationUs / 2) pos.index else pos.index + 1
    }

    fun canSplit(): Boolean = _project.value?.let { Timeline.canSplit(it.clips, _playheadUs.value) } ?: false

    fun split() = commit { p ->
        Timeline.split(p.clips, _playheadUs.value, ProjectRepository.newId())?.let { p.copy(clips = it) } ?: p
    }

    fun deleteTarget() {
        val i = targetIndex()
        if (i < 0) return
        commit { p -> p.copy(clips = Timeline.delete(p.clips, i)) }
    }

    fun duplicateTarget() {
        val i = targetIndex()
        if (i < 0) return
        commit { p -> p.copy(clips = Timeline.duplicate(p.clips, i, ProjectRepository.newId())) }
    }

    fun move(from: Int, to: Int) = commit { p -> p.copy(clips = Timeline.move(p.clips, from, to)) }

    fun addClips(refs: List<String>, at: Int? = null, onDone: (added: Int) -> Unit) {
        viewModelScope.launch {
            val clips = engine.importClips(refs)
            if (clips.isNotEmpty()) {
                commit { p -> p.copy(clips = Timeline.insert(p.clips, at ?: p.clips.size, clips)) }
            }
            onDone(clips.size)
        }
    }

    fun removeMissing() {
        val gone = _missing.value
        _missing.value = emptySet()
        commit { p -> p.copy(clips = p.clips.filterNot { it.id in gone }) }
        _project.value?.let { preview.setClips(it.clips, 0L, force = true) }
    }

    // ---------- Playback ----------

    fun togglePlay() {
        if (preview.isPlaying) preview.pause() else {
            val p = _project.value ?: return
            if (p.clips.isEmpty()) return
            if (_playheadUs.value >= p.durationUs - 50_000) seek(0L, exact = true)
            preview.play()
        }
    }

    fun pause() = preview.pause()

    fun seek(timeUs: Long, exact: Boolean) {
        val total = _project.value?.durationUs ?: return
        val t = timeUs.coerceIn(0L, total)
        _playheadUs.value = t
        preview.seekTo(t, exact)
    }

    // ---------- Trim mode ----------

    private var trimIndex = -1
    private val _trimRange = MutableStateFlow(0L to 0L)
    val trimRange: StateFlow<Pair<Long, Long>> = _trimRange.asStateFlow()

    fun trimClip(): Clip? = _project.value?.clips?.getOrNull(trimIndex)

    fun startTrim() {
        val i = targetIndex()
        val clip = _project.value?.clips?.getOrNull(i) ?: return
        if (clip.isPhoto) {
            openPanel(Mode.Duration)
            return
        }
        preview.pause()
        trimIndex = i
        _selectedId.value = clip.id
        _trimRange.value = clip.trimStartUs to clip.trimEndUs
        _mode.value = Mode.Trim
        preview.enterSource(clip, clip.trimStartUs)
    }

    fun setTrim(startUs: Long, endUs: Long, seekToStart: Boolean, exact: Boolean) {
        val clip = trimClip() ?: return
        val r = Timeline.clampTrim(clip.sourceDurationUs, startUs, endUs)
        _trimRange.value = r
        preview.seekSource(if (seekToStart) r.first else r.second - 33_000, exact)
    }

    fun toggleTrimPlay() {
        if (preview.isPlaying) {
            preview.pause()
            return
        }
        val (s, e) = _trimRange.value
        val pos = preview.sourcePositionUs()
        if (pos < s || pos >= e - 50_000) preview.seekSource(s, exact = true)
        preview.play()
        viewModelScope.launch {
            while (preview.isPlaying && _mode.value == Mode.Trim) {
                if (preview.sourcePositionUs() >= _trimRange.value.second) {
                    preview.pause()
                    break
                }
                delay(20)
            }
        }
    }

    fun trimPositionUs(): Long = preview.sourcePositionUs()

    fun finishTrim(apply: Boolean) {
        val index = trimIndex
        _mode.value = Mode.Edit
        trimIndex = -1
        val p = _project.value ?: return
        if (apply && index in p.clips.indices) {
            val (s, e) = _trimRange.value
            commit { it.copy(clips = Timeline.trim(it.clips, index, s, e)) }
        }
        val clips = _project.value?.clips ?: return
        val start = Timeline.startsUs(clips).getOrElse(index) { 0L }
        _playheadUs.value = start.coerceAtMost(Timeline.totalUs(clips))
        preview.exitSource(clips, _playheadUs.value)
    }

    // ---------- Panels: Speed, Volume, Transform, Canvas, Duration ----------
    // Changes show live while a panel is open and become a single undo step on Done.

    private var panelSnapshot: Project? = null
    private var panelIndex = -1

    fun panelClip(): Clip? = _project.value?.clips?.getOrNull(panelIndex)

    fun openPanel(mode: Mode) {
        val p = _project.value ?: return
        val i = targetIndex()
        if (mode != Mode.Canvas && i < 0) return
        preview.pause()
        panelIndex = i
        panelSnapshot = p
        if (i >= 0 && mode != Mode.Canvas) {
            _selectedId.value = p.clips[i].id
            val start = Timeline.startsUs(p.clips)[i]
            val end = start + p.clips[i].durationUs
            // Stay where the user is if the playhead is already on this clip.
            if (_playheadUs.value !in start until end) seek(start, exact = true)
        }
        _mode.value = mode
    }

    private fun stage(transform: (Project) -> Project) {
        val p = _project.value ?: return
        val next = transform(p)
        if (next == p) return
        _project.value = next
        if (_playheadUs.value > next.durationUs) _playheadUs.value = next.durationUs
        syncPreviewAudio(next)
        if (_missing.value.isEmpty()) preview.setClips(next.clips, _playheadUs.value)
    }

    /** Changes the clip the open panel is for. */
    fun stageClip(transform: (Clip) -> Clip) =
        stage { p -> p.copy(clips = Timeline.update(p.clips, panelIndex, transform)) }

    /** Changes every clip (the "apply to all" buttons). */
    fun stageAll(transform: (Clip) -> Clip) = stage { p -> p.copy(clips = p.clips.map(transform)) }

    fun stageCanvas(transform: (Canvas) -> Canvas) = stage { p -> p.copy(canvas = transform(p.canvas)) }

    fun setSpeed(speed: Float) = stageClip {
        it.copy(speed = speed.coerceIn(Clip.MIN_SPEED, Clip.MAX_SPEED))
    }

    fun setVolume(volume: Float) = stageClip { it.copy(volume = volume.coerceIn(0f, Clip.MAX_VOLUME)) }

    fun setPhotoDuration(durationUs: Long) = stageClip {
        val d = durationUs.coerceIn(Timeline.MIN_CLIP_US, Clip.MAX_PHOTO_US - it.trimStartUs)
        it.copy(trimEndUs = it.trimStartUs + d)
    }

    fun rotate() = stageClip { it.copy(rotation = (it.rotation + 90) % 360) }

    fun flip() = stageClip { it.copy(flipH = !it.flipH) }

    fun setFill(fill: Boolean) = stageClip { it.copy(fill = fill) }

    fun closePanel(apply: Boolean) {
        if (recorder != null) cancelRecording()
        _selectedAudioId.value = null
        // An empty new text box is not worth keeping.
        val overlayId = _selectedOverlayId.value
        if (apply && overlayId != null) {
            val o = _project.value?.overlays?.firstOrNull { it.id == overlayId }
            if (o != null && o.isText && o.text.isBlank()) removeOverlayStaged(overlayId)
        }
        _selectedOverlayId.value = null
        overlayIsNew = false
        val before = panelSnapshot
        panelSnapshot = null
        panelIndex = -1
        _mode.value = Mode.Edit
        val now = _project.value ?: return
        if (before == null) return
        if (!apply) {
            stage { before }
            return
        }
        if (now != before) {
            undoStack.addLast(before)
            if (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear()
            apply(now.copy(updatedAt = nowMillis()))
        }
    }

    // ---------- Text and logo overlays ----------

    private fun openOverlayPanel(o: Overlay, isNew: Boolean, snapshot: Project) {
        panelSnapshot = snapshot
        panelIndex = -1
        _selectedId.value = null
        _selectedOverlayId.value = o.id
        overlayIsNew = isNew
        _mode.value = if (o.isText) Mode.Text else Mode.Logo
    }

    /** Adds an empty text box at the playhead and opens the Text panel. */
    fun addText() {
        val p = _project.value ?: return
        if (p.clips.isEmpty()) return
        preview.pause()
        val (s, e) = OverlayMath.rangeAt(_playheadUs.value, p.durationUs, Overlay.DEFAULT_TEXT_US)
        val o = Overlay(id = ProjectRepository.newId(), kind = OverlayKind.TEXT, startUs = s, endUs = e, y = 0.5f)
        stage { it.copy(overlays = it.overlays + o) }
        openOverlayPanel(o, isNew = true, snapshot = p)
    }

    /** Adds a picture (logo) over the whole video, top right, and opens the Logo panel. */
    fun addLogo(refs: List<String>, onFail: () -> Unit) {
        viewModelScope.launch {
            val p = _project.value ?: return@launch
            val image = engine.importClips(refs).firstOrNull { it.isPhoto }
            if (image == null) {
                onFail()
                return@launch
            }
            preview.pause()
            val o = Overlay(
                id = ProjectRepository.newId(),
                kind = OverlayKind.LOGO,
                startUs = 0L,
                endUs = p.durationUs.coerceAtLeast(Overlay.MIN_US),
                x = 0.82f,
                y = 0.1f,
                imageUri = image.sourceUri,
                imageWidth = image.width,
                imageHeight = image.height,
            )
            stage { it.copy(overlays = it.overlays + o) }
            openOverlayPanel(o, isNew = true, snapshot = p)
        }
    }

    /** Opens an existing overlay for editing (saving any other open one first). */
    fun editOverlay(o: Overlay) {
        if (_selectedOverlayId.value == o.id) return
        if (_mode.value == Mode.Text || _mode.value == Mode.Logo) closePanel(apply = true)
        else if (_mode.value != Mode.Edit) return
        val p = _project.value ?: return
        preview.pause()
        openOverlayPanel(o, isNew = false, snapshot = p)
    }

    fun stageOverlay(id: String, transform: (Overlay) -> Overlay) =
        stage { p -> p.copy(overlays = p.overlays.map { if (it.id == id) transform(it) else it }) }

    fun stageSelectedOverlay(transform: (Overlay) -> Overlay) {
        val id = _selectedOverlayId.value ?: return
        val total = _project.value?.durationUs ?: 0L
        stageOverlay(id) { o ->
            val n = transform(o)
            val (s, e) = OverlayMath.clampRange(n.startUs, n.endUs, total)
            n.copy(startUs = s, endUs = e)
        }
    }

    private fun removeOverlayStaged(id: String) = stage { p -> p.copy(overlays = p.overlays.filterNot { it.id == id }) }

    fun deleteOverlay() {
        val id = _selectedOverlayId.value ?: return
        removeOverlayStaged(id)
        closePanel(apply = true)
    }

    // ---------- Audio: music and voice-over ----------

    /** Opens the Audio panel (on one sound, if [selectId] is given). */
    fun openAudio(selectId: String? = null) {
        val p = _project.value ?: return
        if (p.clips.isEmpty()) return
        preview.pause()
        panelSnapshot = p
        panelIndex = -1
        _selectedId.value = null
        _selectedAudioId.value = selectId
        _mode.value = Mode.Audio
    }

    /** A bar under the timeline was tapped. */
    fun editAudio(c: AudioClip) {
        when (_mode.value) {
            Mode.Audio -> _selectedAudioId.value = c.id
            Mode.Edit -> openAudio(c.id)
            else -> Unit
        }
    }

    fun selectAudio(id: String?) {
        _selectedAudioId.value = id
    }

    /** Adds picked sound files at the playhead, one after the other. */
    fun addAudio(refs: List<String>, onDone: (added: Int) -> Unit) {
        viewModelScope.launch {
            val p = _project.value ?: return@launch
            val imported = engine.importAudio(refs)
            if (imported.isNotEmpty()) {
                var at = AudioMath.placeAt(_playheadUs.value, p.durationUs)
                val placed = imported.map { c ->
                    AudioMath.normalise(c.copy(startUs = at), p.durationUs).also { at = it.endUs }
                }
                stage { it.copy(audio = it.audio + placed) }
                _selectedAudioId.value = placed.first().id
            }
            onDone(imported.size)
        }
    }

    fun stageSelectedAudio(transform: (AudioClip) -> AudioClip) {
        val id = _selectedAudioId.value ?: return
        val total = _project.value?.durationUs ?: 0L
        stage { p ->
            p.copy(audio = p.audio.map { if (it.id == id) AudioMath.normalise(transform(it), total) else it })
        }
    }

    fun deleteSelectedAudio() {
        val id = _selectedAudioId.value ?: return
        stage { p -> p.copy(audio = p.audio.filterNot { it.id == id }) }
        _selectedAudioId.value = null
    }

    /** Switches the video clips' own sound on or off (part of the open panel's edit). */
    fun stageMuteOriginal() = stage { it.copy(muteOriginal = !it.muteOriginal) }

    /**
     * Starts a voice-over: the video plays from the playhead (its sound and the music are silenced)
     * while the microphone records. Returns false if the microphone couldn't be opened.
     */
    fun startRecording(): Boolean {
        val p = _project.value ?: return false
        if (recorder != null || p.clips.isEmpty()) return false
        preview.pause()
        if (_playheadUs.value >= p.durationUs - 50_000) seek(0L, exact = true)
        val r = engine.newRecorder()
        if (!r.start()) return false
        recorder = r
        recordStartUs = _playheadUs.value
        _recordingUs.value = 0L
        syncPreviewAudio(p)
        preview.play()
        return true
    }

    /** Ends the voice-over and adds it at the point where recording began. */
    fun stopRecording(onResult: (saved: Boolean) -> Unit = {}) {
        val r = recorder ?: return
        recorder = null
        _recordingUs.value = null
        preview.pause()
        val startUs = recordStartUs
        viewModelScope.launch {
            val number = (_project.value?.audio?.count { it.isVoice } ?: 0) + 1
            val clip = r.stop("Voice $number")
            val p = _project.value
            if (clip != null && p != null) {
                val placed = AudioMath.normalise(clip.copy(startUs = startUs), p.durationUs)
                stage { it.copy(audio = it.audio + placed) }
                _selectedAudioId.value = placed.id
            }
            _project.value?.let(::syncPreviewAudio)
            onResult(clip != null)
        }
    }

    fun cancelRecording() {
        val r = recorder ?: return
        recorder = null
        _recordingUs.value = null
        preview.pause()
        r.cancel()
        _project.value?.let(::syncPreviewAudio)
    }

    // ---------- Reorder mode ----------

    fun startReorder() {
        preview.pause()
        _mode.value = Mode.Reorder
    }

    fun finishReorder() {
        _mode.value = Mode.Edit
    }

    // ---------- Export ----------

    fun startExport(
        settings: ExportSettings,
        renderLayers: suspend (overlays: List<Overlay>, width: Int, height: Int) -> List<OverlayLayer>,
    ) {
        val p = _project.value ?: return
        if (p.clips.isEmpty() || _export.value is ExportState.Running) return
        preview.pause()
        if (p.export != settings) commit { it.copy(export = settings) }
        val edited = _project.value ?: p
        _export.value = ExportState.Running(0)
        exportJob = viewModelScope.launch {
            _export.value = try {
                // Only sounds that can still be opened, cut to the video's length.
                val toExport = withContext(Dispatchers.Default) {
                    edited.copy(
                        audio = AudioMath.fit(edited.audio.filter { engine.isReadable(it.sourceUri) }, edited.durationUs),
                    )
                }
                val size = CanvasMath.outputSize(toExport.clips, toExport.canvas, settings.height)
                val layers = withContext(Dispatchers.Default) {
                    renderLayers(toExport.overlays, size.width, size.height)
                }
                val ref = engine.export(toExport, settings, layers) { pct ->
                    _export.value = ExportState.Running(pct.coerceIn(0, 100))
                }
                ExportState.Done(ref)
            } catch (e: CancellationException) {
                ExportState.Idle
            } catch (e: ExportFailed) {
                ExportState.Failed(e.message ?: "Export failed.")
            } catch (e: Exception) {
                ExportState.Failed(e.message ?: "Export failed.")
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _export.value = ExportState.Idle
    }

    fun resetExport() {
        if (_export.value !is ExportState.Running) _export.value = ExportState.Idle
    }

    override fun onCleared() {
        tickJob?.cancel()
        exportJob?.cancel()
        recorder?.cancel()
        // Save immediately; the scope is going away.
        _project.value?.let { p ->
            services.appScope.launch { repo.save(p) }
        }
        preview.release()
    }
}
