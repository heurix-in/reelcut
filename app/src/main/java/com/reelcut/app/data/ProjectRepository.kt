package com.reelcut.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One JSON file per project in app-private storage. Saves are atomic (temp file, then rename). */
class ProjectRepository(private val dir: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Mutex()
    private val _projects = MutableStateFlow<List<Project>>(emptyList())

    /** All projects, newest edit first. Call [refresh] to load. */
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    init {
        dir.mkdirs()
    }

    private fun fileFor(id: String) = File(dir, "$id.json")

    suspend fun refresh() {
        val list = withContext(Dispatchers.IO) {
            lock.withLock {
                dir.listFiles { f -> f.name.endsWith(".json") }
                    ?.mapNotNull { f ->
                        runCatching { json.decodeFromString(Project.serializer(), f.readText()) }.getOrNull()
                    }
                    ?.sortedByDescending { it.updatedAt }
                    ?: emptyList()
            }
        }
        _projects.value = list
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = fileFor(id)
            if (!f.exists()) null
            else runCatching { json.decodeFromString(Project.serializer(), f.readText()) }.getOrNull()
        }
    }

    suspend fun save(project: Project) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val target = fileFor(project.id)
                val tmp = File(dir, "${project.id}.json.tmp")
                tmp.writeText(json.encodeToString(Project.serializer(), project))
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }
        }
        _projects.value = (_projects.value.filterNot { it.id == project.id } + project)
            .sortedByDescending { it.updatedAt }
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { lock.withLock { fileFor(id).delete() } }
        _projects.value = _projects.value.filterNot { it.id == id }
    }

    suspend fun duplicate(project: Project): Project {
        val now = System.currentTimeMillis()
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
        fun newId(): String = UUID.randomUUID().toString()
    }
}
