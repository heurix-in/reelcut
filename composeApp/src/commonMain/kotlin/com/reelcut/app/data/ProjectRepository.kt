package com.reelcut.app.data

import com.reelcut.app.newId
import com.reelcut.app.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Where project JSON lives: app-private files on Android, %APPDATA%\Reelcut on Windows. */
interface ProjectStore {
    suspend fun readAll(): List<String>
    suspend fun read(id: String): String?
    suspend fun write(id: String, text: String)
    suspend fun delete(id: String)
}

/** All projects, as JSON in a [ProjectStore]. */
class ProjectRepository(private val store: ProjectStore) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Mutex()
    private val _projects = MutableStateFlow<List<Project>>(emptyList())

    /** All projects, newest edit first. Call [refresh] to load. */
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private fun decode(text: String): Project? =
        runCatching { json.decodeFromString(Project.serializer(), text) }.getOrNull()

    suspend fun refresh() {
        val list = lock.withLock { store.readAll().mapNotNull(::decode) }
        _projects.value = list.sortedByDescending { it.updatedAt }
    }

    suspend fun load(id: String): Project? = lock.withLock { store.read(id)?.let(::decode) }

    suspend fun save(project: Project) {
        lock.withLock { store.write(project.id, json.encodeToString(Project.serializer(), project)) }
        _projects.value = (_projects.value.filterNot { it.id == project.id } + project)
            .sortedByDescending { it.updatedAt }
    }

    suspend fun delete(id: String) {
        lock.withLock { store.delete(id) }
        _projects.value = _projects.value.filterNot { it.id == id }
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
