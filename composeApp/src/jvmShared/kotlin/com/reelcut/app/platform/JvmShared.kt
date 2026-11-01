package com.reelcut.app

import com.reelcut.app.data.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// Compiled into both the Android and the Windows app (see composeApp/build.gradle.kts).

actual fun newId(): String = UUID.randomUUID().toString()

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun formatDate(millis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))

/** One JSON file per project in [dir]. Saves are atomic (temp file, then rename). */
class FileProjectStore(private val dir: File) : ProjectStore {
    init {
        dir.mkdirs()
    }

    private fun fileFor(id: String) = File(dir, "$id.json")

    override suspend fun readAll(): List<String> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { it.readText() }.getOrNull() }
            ?: emptyList()
    }

    override suspend fun read(id: String): String? = withContext(Dispatchers.IO) {
        fileFor(id).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    }

    override suspend fun write(id: String, text: String) = withContext(Dispatchers.IO) {
        val target = fileFor(id)
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        Unit
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        fileFor(id).delete()
        Unit
    }
}
