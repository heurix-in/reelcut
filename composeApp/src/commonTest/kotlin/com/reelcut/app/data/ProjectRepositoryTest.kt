package com.reelcut.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRepositoryTest {
    private val store = InMemoryStore()
    private val repo = ProjectRepository(store)

    @Test
    fun savedProjectComesBackIdentical() = runTest {
        val p = fullProject()
        repo.save(p)
        assertEquals(p, repo.load(p.id))
        repo.refresh()
        assertEquals(listOf(p), repo.projects.value)
        assertTrue(repo.unreadable.value.isEmpty())
    }

    @Test
    fun projectsAreListedNewestEditFirst() = runTest {
        repo.save(fullProject("old", updatedAt = 10))
        repo.save(fullProject("new", updatedAt = 30))
        repo.save(fullProject("mid", updatedAt = 20))
        repo.refresh()
        assertEquals(listOf("new", "mid", "old"), repo.projects.value.map { it.id })
    }

    @Test
    fun projectFromAnOlderBuildOpensWithDefaults() = runTest {
        store.files["old"] = """
            {"schemaVersion":1,"id":"old","name":"Old","createdAt":1,"updatedAt":2,
             "clips":[{"id":"c","sourceUri":"content://x","sourceDurationUs":5000000,"trimEndUs":5000000}]}
        """.trimIndent()
        val p = repo.load("old")!!
        assertEquals(1, p.schemaVersion)
        assertEquals(1, p.clips.size)
        assertEquals(1f, p.clips[0].speed)
        assertEquals(ClipKind.VIDEO, p.clips[0].kind)
        assertTrue(p.overlays.isEmpty())
        assertTrue(p.audio.isEmpty())
        assertEquals(false, p.muteOriginal)
        assertEquals(Aspect.ORIGINAL, p.canvas.aspect)
    }

    @Test
    fun fieldsFromANewerBuildAreIgnored() = runTest {
        store.files["new"] = """
            {"schemaVersion":99,"id":"new","name":"New","createdAt":1,"updatedAt":2,"filters":[1,2,3],
             "clips":[{"id":"c","sourceUri":"content://x","sourceDurationUs":5000000,"trimEndUs":5000000,"glow":9}]}
        """.trimIndent()
        val p = repo.load("new")!!
        assertEquals("New", p.name)
        assertEquals(1, p.clips.size)
    }

    @Test
    fun anUnknownChoiceFallsBackToTheDefaultInsteadOfLosingTheProject() = runTest {
        store.files["x"] = """
            {"id":"x","name":"X","createdAt":1,"updatedAt":2,
             "canvas":{"aspect":"R2_35"},
             "clips":[{"id":"c","sourceUri":"content://x","sourceDurationUs":5000000,"trimEndUs":5000000,"kind":"HOLOGRAM"}]}
        """.trimIndent()
        repo.refresh()
        val p = repo.projects.value.single()
        assertEquals(Aspect.ORIGINAL, p.canvas.aspect)
        assertEquals(ClipKind.VIDEO, p.clips[0].kind)
        assertTrue(repo.unreadable.value.isEmpty())
    }

    @Test
    fun aBrokenFileIsReportedNotHiddenAndLeftUntouched() = runTest {
        repo.save(fullProject("good"))
        store.files["broken"] = "{ this is not json"
        store.files["empty"] = ""
        repo.refresh()

        assertEquals(listOf("good"), repo.projects.value.map { it.id })
        assertEquals(listOf("broken", "empty"), repo.unreadable.value.map { it.id })
        assertTrue(repo.unreadable.value.all { it.reason.isNotBlank() })
        // Nothing was changed or deleted.
        assertEquals("{ this is not json", store.files["broken"])
        assertEquals("", store.files["empty"])
        assertNull(repo.load("broken"))
    }

    @Test
    fun aFileThatBecomesReadableLeavesTheUnreadableList() = runTest {
        store.files["p"] = "{ not json yet"
        repo.refresh()
        assertEquals(1, repo.unreadable.value.size)

        store.files["p"] = Json.encodeToString(Project.serializer(), fullProject("p"))
        repo.refresh()
        assertTrue(repo.unreadable.value.isEmpty())
        assertEquals(listOf("p"), repo.projects.value.map { it.id })
    }

    @Test
    fun deleteRemovesTheProject() = runTest {
        repo.save(fullProject("a"))
        repo.save(fullProject("b"))
        repo.delete("a")
        assertEquals(listOf("b"), repo.projects.value.map { it.id })
        assertNull(repo.load("a"))
    }

    @Test
    fun duplicateGetsNewIdsAndLeavesTheOriginalAlone() = runTest {
        val original = fullProject("orig")
        repo.save(original)
        val copy = repo.duplicate(original)

        assertNotEquals(original.id, copy.id)
        assertEquals("Café 🎬 copy", copy.name)
        assertEquals(original.clips.size, copy.clips.size)
        assertTrue(copy.clips.map { it.id }.intersect(original.clips.map { it.id }.toSet()).isEmpty())
        assertEquals(original, repo.load("orig"))
        assertEquals(copy, repo.load(copy.id))
    }

    @Test
    fun aFailedSaveThrowsAndKeepsTheListAsItWas() = runTest {
        val good = fullProject("p", updatedAt = 1)
        repo.save(good)
        store.failWrites = true
        assertFailsWith<IllegalStateException> { repo.save(good.copy(name = "changed", updatedAt = 9)) }
        assertEquals(listOf(good), repo.projects.value)
    }

    @Test
    fun aSaveInProgressIsNotCutShortByCancellation() = runTest {
        store.writeDelayMs = 100
        val p = fullProject("p")
        val job = launch { repo.save(p) }
        runCurrent()
        job.cancel() // a newer edit cancelling the autosave job
        advanceUntilIdle()
        assertEquals(p, repo.load("p"))
        assertEquals(listOf(p), repo.projects.value)
    }
}
