package com.reelcut.app.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deleting a project removes the voice-overs recorded for it, and nothing else. */
class VoiceCleanupTest {
    private val store = InMemoryStore()
    private val removed = mutableListOf<String>()
    private val repo = ProjectRepository(store) { removed += it }

    private fun voice(id: String, uri: String) = AudioClip(
        id = id, kind = AudioKind.VOICE, sourceUri = uri, sourceDurationUs = 3_000_000L, trimEndUs = 3_000_000L,
    )

    private fun music(id: String, uri: String) = AudioClip(
        id = id, kind = AudioKind.MUSIC, sourceUri = uri, sourceDurationUs = 9_000_000L, trimEndUs = 9_000_000L,
    )

    private fun project(id: String, vararg audio: AudioClip) = fullProject(id).copy(audio = audio.toList())

    @Test
    fun deletingAProjectRemovesItsVoiceFilesButNotItsMusic() = runTest {
        repo.save(project("p", voice("v1", "file:///voice/a.m4a"), voice("v2", "file:///voice/b.m4a"), music("m", "content://song")))
        repo.delete("p")
        assertEquals(listOf("file:///voice/a.m4a", "file:///voice/b.m4a"), removed)
    }

    @Test
    fun aRecordingSharedWithADuplicateStaysUntilTheLastProjectGoes() = runTest {
        val original = project("orig", voice("v", "file:///voice/shared.m4a"))
        repo.save(original)
        val copy = repo.duplicate(original)

        repo.delete("orig")
        assertTrue(removed.isEmpty(), "the copy still uses the recording")

        repo.delete(copy.id)
        assertEquals(listOf("file:///voice/shared.m4a"), removed)
    }

    @Test
    fun nothingIsRemovedWhileAProjectCannotBeRead() = runTest {
        repo.save(project("p", voice("v", "file:///voice/a.m4a")))
        store.files["broken"] = "{ nope"
        repo.refresh()
        repo.delete("p")
        assertTrue(removed.isEmpty(), "the unreadable project might use it")
    }

    @Test
    fun aFileThatCannotBeRemovedDoesNotStopTheDelete() = runTest {
        val failing = ProjectRepository(store) { throw IllegalStateException("locked") }
        failing.save(project("p", voice("v", "file:///voice/a.m4a")))
        failing.delete("p")
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun withoutAVoiceFolderDeleteStillWorks() = runTest {
        val plain = ProjectRepository(store)
        plain.save(project("p", voice("v", "file:///voice/a.m4a")))
        plain.delete("p")
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun deletingAMissingProjectRemovesNothing() = runTest {
        repo.delete("nope")
        assertTrue(removed.isEmpty())
    }
}
