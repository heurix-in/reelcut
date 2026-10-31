package com.reelcut.app.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelcut.app.ReelcutApp
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.Project
import com.reelcut.app.data.ProjectRepository
import com.reelcut.app.data.Timeline
import com.reelcut.app.media.Exporter
import com.reelcut.app.media.MediaProbe
import com.reelcut.app.media.PreviewController
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
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

enum class Mode { Edit, Trim, Reorder, Speed, Volume, Transform, Canvas, Duration }

/**
 * Holds the open project and every edit made to it. Each edit makes a new immutable Project;
 * the previous one goes on the undo stack. The project is saved 500 ms after the last edit.
 */
class EditorViewModel(private val app: Application, private val projectId: String) : ViewModel() {

    private val container = (app as ReelcutApp).container
    private val repo = container.repository
    val thumbnails = container.thumbnails
    val preview = PreviewController(app)
    private val exporter = Exporter(app)

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
            _missing.value = withContext(Dispatchers.IO) {
                p.clips.filterNot { MediaProbe.isReadable(app, it.sourceUri) }.map { it.id }.toSet()
            }
            _project.value = p
            if (_missing.value.isEmpty()) preview.setClips(p.clips, 0L)
            refreshUndoFlags()
        }
        tickJob = viewModelScope.launch {
            while (true) {
                if (preview.isPlaying && _mode.value != Mode.Trim) {
                    _playheadUs.value = preview.positionUs()
                }
                _isPlaying.value = preview.isPlaying && preview.player.playbackState != androidx.media3.common.Player.STATE_ENDED
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
        apply(next.copy(updatedAt = System.currentTimeMillis()))
    }

    private fun apply(next: Project) {
        _project.value = next
        val total = next.durationUs
        if (_playheadUs.value > total) _playheadUs.value = total
        if (_selectedId.value != null && next.clips.none { it.id == _selectedId.value }) _selectedId.value = null
        if (_mode.value != Mode.Trim && _missing.value.isEmpty()) preview.setClips(next.clips, _playheadUs.value)
        refreshUndoFlags()
        scheduleSave()
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

    /** The clip tools act on: the selected one, or the one under the playhead. */
    fun targetIndex(): Int {
        val p = _project.value ?: return -1
        _selectedId.value?.let { id -> p.clips.indexOfFirst { it.id == id }.let { if (it >= 0) return it } }
        return Timeline.locate(p.clips, _playheadUs.value)?.index ?: -1
    }

    fun select(id: String?) {
        _selectedId.value = if (_selectedId.value == id) null else id
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

    fun addClips(uris: List<Uri>, at: Int? = null, onDone: (added: Int) -> Unit) {
        viewModelScope.launch {
            val clips = MediaProbe.clipsFrom(app, uris)
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
        if (i >= 0) {
            _selectedId.value = p.clips[i].id
            seek(Timeline.startsUs(p.clips)[i], exact = true)
        }
        _mode.value = mode
    }

    private fun stage(transform: (Project) -> Project) {
        val p = _project.value ?: return
        val next = transform(p)
        if (next == p) return
        _project.value = next
        if (_playheadUs.value > next.durationUs) _playheadUs.value = next.durationUs
        if (_missing.value.isEmpty()) preview.setClips(next.clips, _playheadUs.value)
    }

    /** Changes the clip the open panel is for. */
    fun stageClip(transform: (Clip) -> Clip) =
        stage { p -> p.copy(clips = Timeline.update(p.clips, panelIndex, transform)) }

    /** Changes every clip (the "apply to all" buttons). */
    fun stageAll(transform: (Clip) -> Clip) = stage { p -> p.copy(clips = p.clips.map(transform)) }

    fun stageCanvas(transform: (com.reelcut.app.data.Canvas) -> com.reelcut.app.data.Canvas) =
        stage { p -> p.copy(canvas = transform(p.canvas)) }

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
            apply(now.copy(updatedAt = System.currentTimeMillis()))
        }
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

    fun startExport(settings: ExportSettings) {
        val p = _project.value ?: return
        if (p.clips.isEmpty() || _export.value is ExportState.Running) return
        preview.pause()
        if (p.export != settings) commit { it.copy(export = settings) }
        _export.value = ExportState.Running(0)
        exportJob = viewModelScope.launch {
            _export.value = try {
                val uri = exporter.export(p.clips, p.canvas, settings) { pct ->
                    _export.value = ExportState.Running(pct.coerceIn(0, 100))
                }
                ExportState.Done(uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                ExportState.Idle
            } catch (e: Exporter.ExportFailed) {
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
        // Save immediately; the scope is going away.
        _project.value?.let { p ->
            container.appScope.launch { repo.save(p) }
        }
        preview.release()
    }
}
