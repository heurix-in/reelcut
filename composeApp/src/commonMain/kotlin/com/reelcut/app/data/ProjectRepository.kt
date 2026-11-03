package com.reelcut.app.data

import com.reelcut.app.newId
import com.reelcut.app.nowMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** One project file as it sits in the store: its id (the file name) and its raw JSON text. */
class StoredProject(val id: String, val text: String)

/** Where project JSON lives: app-private files on Android, %APPDATA%\Reelcut on Windows. */
interface ProjectStore {
    suspend fun readAll(): List<StoredProject>
    suspend fun read(id: String): String?
    suspend fun write(id: String, text: String)
    suspend fun delete(id: String)
}

/** Removes a recorded voice-over file. Implementations only ever touch the app's own voice folder. */
fun interface VoiceFiles {
    fun delete(ref: String)
}

/** A project file that exists but could not be understood. It is never changed or removed. */
data class UnreadableProject(val id: String, val reason: String)

/**
 * All projects, as JSON in a [ProjectStore]. When a project is deleted, the voice-overs recorded
 * for it are removed through [voiceFiles] (unless another project still uses them).
 */
class ProjectRepository(private val store: ProjectStore, private val voiceFiles: VoiceFiles? = null) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // A value this build does not know (an enum from another version, a null where a value is
        // required) falls back to the field's default, so the project still opens.
        coerceInputValues = true
    }
    private val lock = Mutex()
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _unreadable = MutableStateFlow<List<UnreadableProject>>(emptyList())

    /** All projects, newest edit first. Call [refresh] to load. */
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    /** Project files found by the last [refresh] that could not be opened (left untouched on disk). */
    val unreadable: StateFlow<List<UnreadableProject>> = _unreadable.asStateFlow()

    private fun decode(text: String): Result<Project> =
        runCatching { json.decodeFromString(Project.serializer(), text) }

    suspend fun refresh() {
        val stored = lock.withLock { store.readAll() }
        val good = ArrayList<Project>()
        val bad = ArrayList<UnreadableProject>()
        stored.forEach { s ->
            decode(s.text).fold(
                onSuccess = { good += it },
                onFailure = { bad += UnreadableProject(s.id, it.message?.lineSequence()?.firstOrNull() ?: "unreadable") },
            )
        }
        _projects.value = good.sortedByDescending { it.updatedAt }
        _unreadable.value = bad.sortedBy { it.id }
    }

    suspend fun load(id: String): Project? = lock.withLock { store.read(id)?.let { decode(it).getOrNull() } }

    /**
     * Writes the project. Not cancellable: a newer edit must not stop a save half-way and leave
     * the list on screen different from the file.
     */
    suspend fun save(project: Project) = withContext(NonCancellable) {
        lock.withLock { store.write(project.id, json.encodeToString(Project.serializer(), project)) }
        _projects.value = (_projects.value.filterNot { it.id == project.id } + project)
            .sortedByDescending { it.updatedAt }
    }

    suspend fun delete(id: String) {
        val doomed = lock.withLock { store.read(id) }?.let { decode(it).getOrNull() }
        lock.withLock { store.delete(id) }
        _projects.value = _projects.value.filterNot { it.id == id }
        if (doomed != null) removeUnusedVoiceFiles(doomed)
    }

    /**
     * Voice-overs are files only this app made, so they go with their project. A duplicate shares
     * its recordings with the original, and a project that could not be read might still use one,
     * so a file is removed only when every project is readable and none of them refers to it.
     */
    private suspend fun removeUnusedVoiceFiles(gone: Project) {
        val files = voiceFiles ?: return
        if (_unreadable.value.isNotEmpty()) return
        val stillUsed = _projects.value.flatMap { it.audio }.map { it.sourceUri }.toSet()
        val unused = gone.audio.filter { it.isVoice && it.sourceUri !in stillUsed }.map { it.sourceUri }.distinct()
        if (unused.isEmpty()) return
        withContext(Dispatchers.Default) { unused.forEach { runCatching { files.delete(it) } } }
    }

    suspend fun duplicate(project: Project): Project {
        val now = nowMillis()
        val copy = project.copy(
            id = newId(),
            name = "${project.name} copy",
            createdAt = now,
            updatedAt = now,
            clips = project.clips.map { it.copy(id = newId()) },
        )
        save(copy)
        return copy
    }

    companion object {
        fun newId(): String = com.reelcut.app.newId()
    }
}
