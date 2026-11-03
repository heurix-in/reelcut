package com.reelcut.app.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Text styling is stored in the project, so older projects must still open and new ones must round-trip. */
class OverlayModelTest {
    private val store = InMemoryStore()
    private val repo = ProjectRepository(store)

    private fun withOverlay(overlayJson: String) =
        """{"id":"p","name":"P","createdAt":1,"updatedAt":2,"overlays":[$overlayJson]}"""

    @Test
    fun styleFieldsSurviveSavingAndLoading() = runTest {
        repo.save(fullProject("p"))
        val o = repo.load("p")!!.overlays.first { it.isText }
        assertEquals(TextFont.SCRIPT, o.font)
        assertTrue(o.italic)
        assertTrue(o.underline)
        assertTrue(o.uppercase)
        assertEquals(0.12f, o.letterSpacing)
    }

    @Test
    fun aTextBoxFromAnOlderBuildOpensWithPlainStyle() = runTest {
        store.files["p"] = withOverlay("""{"id":"t","kind":"TEXT","startUs":0,"endUs":1000000,"text":"Hi","font":"SERIF"}""")
        val o = repo.load("p")!!.overlays.single()
        assertEquals(TextFont.SERIF, o.font)
        assertFalse(o.italic)
        assertFalse(o.underline)
        assertFalse(o.uppercase)
        assertEquals(0f, o.letterSpacing)
    }

    @Test
    fun aFontThisBuildDoesNotKnowFallsBackToTheDefaultInsteadOfLosingTheProject() = runTest {
        store.files["p"] = withOverlay("""{"id":"t","kind":"TEXT","startUs":0,"endUs":1000000,"text":"Hi","font":"COMIC_SANS"}""")
        repo.refresh()
        assertEquals(TextFont.BOLD, repo.projects.value.single().overlays.single().font)
        assertTrue(repo.unreadable.value.isEmpty())
    }

    @Test
    fun theOriginalFourFontsKeepTheirNamesAndTheNewOnesComeAfterThem() {
        // Projects store the name, so the first four must never be renamed or reordered.
        assertEquals(listOf("SANS", "BOLD", "SERIF", "MONO"), TextFont.entries.take(4).map { it.name })
        assertEquals(10, TextFont.entries.size)
        assertEquals(TextFont.entries.size, TextFont.entries.map { it.label }.toSet().size)
    }
}
