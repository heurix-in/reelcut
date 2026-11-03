package com.reelcut.app

import com.reelcut.app.data.ProjectStore
import com.reelcut.app.data.StoredProject
import com.reelcut.app.data.VoiceFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// Compiled into both the Android and the Windows app (see composeApp/build.gradle.kts).

actual fun newId(): String = UUID.randomUUID().toString()

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun formatDate(millis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))

/**
 * Deletes recorded voice-overs, but only files directly inside [dir]: a project can refer to a
 * file by path or `file:` URI, and anything outside the app's voice folder is left alone.
 */
class VoiceFolder(private val dir: File) : VoiceFiles {
    override fun delete(ref: String) {
        val file = if (ref.startsWith("file:")) File(URI(ref)) else File(ref)
        val canonical = file.canonicalFile
        if (canonical.parentFile == dir.canonicalFile) canonical.delete()
    }
}

/**
 * One JSON file per project in [dir]. A save writes `<id>.json.tmp`, flushes it to disk, then swaps
 * it over `<id>.json` in one atomic step, so the file is always either the old or the new version.
 */
class FileProjectStore(private val dir: File) : ProjectStore {
    init {
        dir.mkdirs()
    }

    private fun fileFor(id: String) = File(dir, "$id.json")
    private fun tmpFor(id: String) = File(dir, "$id.json.tmp")

    @Volatile
    private var recovered = false

    /**
     * A save cut short leaves `<id>.json.tmp`. If the real file is still there the temp one is
     * junk; if the real file is gone (older builds deleted it before renaming) the temp one holds
     * the project, so it is put back rather than thrown away.
     */
    private fun recoverInterrupted() {
        if (recovered) return
        recovered = true
        dir.listFiles { f -> f.name.endsWith(TMP) }?.forEach { tmp ->
            val target = File(dir, tmp.name.removeSuffix(".tmp"))
            if (target.exists()) tmp.delete() else runCatching { replace(tmp, target) }
        }
    }

    /** Moves [from] over [to] atomically; works on Windows too, where File.renameTo refuses to replace. */
    private fun replace(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Files that cannot be read come back empty, so the repository reports them instead of hiding them. */
    override suspend fun readAll(): List<StoredProject> = withContext(Dispatchers.IO) {
        recoverInterrupted()
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.map { StoredProject(it.name.removeSuffix(".json"), runCatching { it.readText() }.getOrDefault("")) }
            ?: emptyList()
    }

    override suspend fun read(id: String): String? = withContext(Dispatchers.IO) {
        recoverInterrupted()
        fileFor(id).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    }

    override suspend fun write(id: String, text: String) = withContext(Dispatchers.IO) {
        recoverInterrupted()
        val tmp = tmpFor(id)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            replace(tmp, fileFor(id))
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        fileFor(id).delete()
        tmpFor(id).delete()
        Unit
    }

    private companion object {
        const val TMP = ".json.tmp"
    }
}
