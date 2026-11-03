package com.reelcut.app.platform

import com.reelcut.app.FileProjectStore
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileProjectStoreTest {
    private val dir: File = createTempDirectory("reelcut-store").toFile()
    private val store = FileProjectStore(dir)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun names() = dir.list()!!.sorted()

    @Test
    fun writeThenReadReturnsTheText() = runTest {
        store.write("a", """{"name":"Café 🎬"}""")
        assertEquals("""{"name":"Café 🎬"}""", store.read("a"))
        assertEquals(listOf("a.json"), names())
    }

    @Test
    fun overwritingReplacesTheFileAndLeavesNoTempFile() = runTest {
        store.write("a", "first")
        store.write("a", "second, longer text")
        store.write("a", "third")
        assertEquals("third", store.read("a"))
        assertEquals(listOf("a.json"), names())
    }

    @Test
    fun readAllListsEveryProjectFileWithItsId() = runTest {
        store.write("one", "1")
        store.write("two", "2")
        File(dir, "notes.txt").writeText("not a project")
        val all = store.readAll().associate { it.id to it.text }
        assertEquals(mapOf("one" to "1", "two" to "2"), all)
    }

    @Test
    fun aFileThatCannotBeReadIsStillListedSoItIsNotHidden() = runTest {
        File(dir, "stuck.json").mkdir() // reading a directory as text fails
        val all = store.readAll()
        assertEquals(listOf("stuck"), all.map { it.id })
        assertEquals("", all.single().text)
    }

    @Test
    fun aFinishedTempFileWithoutItsProjectIsPutBack() = runTest {
        // What an older build left behind if it stopped between deleting the file and renaming.
        File(dir, "lost.json.tmp").writeText("""{"saved":"yes"}""")
        assertEquals("""{"saved":"yes"}""", store.read("lost"))
        assertEquals(listOf("lost.json"), names())
    }

    @Test
    fun aLeftoverTempFileNextToItsProjectIsDiscarded() = runTest {
        File(dir, "p.json").writeText("good")
        File(dir, "p.json.tmp").writeText("half writ")
        assertEquals("good", store.read("p"))
        assertEquals(listOf("p.json"), names())
    }

    @Test
    fun aFailedWriteLeavesNoTempFileAndThrows() = runTest {
        // A non-empty folder where the project file should go makes the final swap fail.
        File(dir, "blocked.json").apply { mkdir(); File(this, "x").writeText("x") }
        assertFailsWith<Exception> { store.write("blocked", "data") }
        assertFalse(File(dir, "blocked.json.tmp").exists())
    }

    @Test
    fun deleteRemovesTheFileAndAnyTempFile() = runTest {
        store.write("a", "x")
        File(dir, "a.json.tmp").writeText("stale")
        store.delete("a")
        assertNull(store.read("a"))
        assertTrue(names().isEmpty())
    }

    @Test
    fun readingAMissingProjectGivesNull() = runTest {
        assertNull(store.read("nope"))
    }
}
