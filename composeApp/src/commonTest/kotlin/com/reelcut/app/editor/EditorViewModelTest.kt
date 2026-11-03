package com.reelcut.app.editor

import androidx.lifecycle.viewModelScope
import com.reelcut.app.data.AudioClip
import com.reelcut.app.data.AudioKind
import com.reelcut.app.data.Clip
import com.reelcut.app.data.ExportSettings
import com.reelcut.app.data.InMemoryStore
import com.reelcut.app.data.OverlayKind
import com.reelcut.app.data.Project
import com.reelcut.app.data.ProjectRepository
import com.reelcut.app.data.Quality
import com.reelcut.app.data.sampleClip
import com.reelcut.app.engine.ExportFailed
import com.reelcut.app.engine.Services
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's state machine, run against a fake engine: every edit makes one undo step, panels
 * change the project live but only count when confirmed, and the preview always follows the project.
 *
 * Three 4-second clips a, b, c (12 s in all) unless a test says otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val store = InMemoryStore()
    private val engine = FakeEngine()
    private val repo = ProjectRepository(store)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun threeClips() = listOf(sampleClip("a"), sampleClip("b"), sampleClip("c"))

    private fun project(clips: List<Clip> = threeClips()) =
        Project(id = "p", name = "Test", createdAt = 1, updatedAt = 1, clips = clips)

    private fun EditorViewModel.p(): Project = project.value!!

    private fun EditorViewModel.ids(): List<String> = p().clips.map { it.id }

    /**
     * Opens the editor on [initial] (null = no such project). The view model's endless playback
     * ticker is stopped at the end so the test can finish.
     */
    private fun editorTest(initial: Project? = project(), body: suspend TestScope.(EditorViewModel) -> Unit) = runTest {
        if (initial != null) {
            repo.save(initial)
            store.writes = 0
        }
        val vm = EditorViewModel(Services(repo, engine, appScope), "p")
        try {
            if (initial != null) vm.project.first { it != null } else vm.loadFailed.first { it }
            body(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ---------- Opening ----------

    @Test
    fun opensTheProjectAndGivesItsClipsToThePreview() = editorTest { vm ->
        assertEquals(listOf("a", "b", "c"), vm.ids())
        assertEquals(vm.p().clips, engine.preview.clips)
        assertFalse(vm.canUndo.value)
        assertFalse(vm.canRedo.value)
        assertEquals(Mode.Edit, vm.mode.value)
        assertFalse(vm.loadFailed.value)
    }

    @Test
    fun aProjectThatDoesNotExistReportsLoadFailed() = editorTest(initial = null) { vm ->
        assertTrue(vm.loadFailed.value)
        assertNull(vm.project.value)
    }

    @Test
    fun clipsWhoseFileIsGoneAreMarkedMissingAndNotPlayed() {
        engine.unreadable += "content://media/b"
        editorTest { vm ->
            assertEquals(setOf("b"), vm.missing.value)
            assertEquals(0, engine.preview.setClipsCalls)

            vm.removeMissing()
            assertEquals(listOf("a", "c"), vm.ids())
            assertTrue(vm.missing.value.isEmpty())
            assertEquals(listOf("a", "c"), engine.preview.clips.map { it.id })
            assertTrue(engine.preview.lastForce)
        }
    }

    // ---------- Undo / redo ----------

    @Test
    fun splitCutsTheClipUnderThePlayheadAndUndoRedoStepsThroughIt() = editorTest { vm ->
        vm.seek(2_000_000L, exact = true)
        assertTrue(vm.canSplit())

        vm.split()
        assertEquals(4, vm.p().clips.size)
        assertEquals(12_000_000L, vm.p().durationUs)
        val (first, second) = vm.p().clips
        assertEquals(2_000_000L, first.trimEndUs)
        assertEquals(2_000_000L, second.trimStartUs)
        assertEquals(first.sourceUri, second.sourceUri)
        assertNotEquals(first.id, second.id)
        assertEquals(vm.p().clips, engine.preview.clips)

        vm.undo()
        assertEquals(listOf("a", "b", "c"), vm.ids())
        assertTrue(vm.canRedo.value)
        assertFalse(vm.canUndo.value)

        vm.redo()
        assertEquals(4, vm.p().clips.size)
        assertFalse(vm.canRedo.value)
    }

    @Test
    fun splitTooCloseToAClipEdgeDoesNothing() = editorTest { vm ->
        vm.seek(50_000L, exact = true)
        assertFalse(vm.canSplit())
        vm.split()
        assertEquals(3, vm.p().clips.size)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun aNewEditAfterUndoClearsRedo() = editorTest { vm ->
        vm.rename("One")
        vm.undo()
        assertTrue(vm.canRedo.value)
        vm.rename("Two")
        assertFalse(vm.canRedo.value)
        assertEquals("Two", vm.p().name)
    }

    @Test
    fun undoKeepsTheLast50StepsOnly() = editorTest { vm ->
        repeat(60) { vm.rename("n$it") }
        repeat(50) { vm.undo() }
        assertEquals("n9", vm.p().name)
        assertFalse(vm.canUndo.value)
        vm.undo() // nothing left
        assertEquals("n9", vm.p().name)
        repeat(50) { vm.redo() }
        assertEquals("n59", vm.p().name)
    }

    @Test
    fun anEditThatChangesNothingIsNotAnUndoStep() = editorTest { vm ->
        vm.rename("Test") // same name
        vm.move(1, 1)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun editsUpdateTheModifiedTime() = editorTest { vm ->
        val before = vm.p().updatedAt
        vm.rename("Later")
        assertTrue(vm.p().updatedAt > before)
    }

    // ---------- Clip tools ----------

    @Test
    fun deleteRemovesTheSelectedClipAndClearsTheSelection() = editorTest { vm ->
        vm.select("b")
        vm.deleteTarget()
        assertEquals(listOf("a", "c"), vm.ids())
        assertNull(vm.selectedId.value)
        assertEquals(8_000_000L, vm.p().durationUs)
        vm.undo()
        assertEquals(listOf("a", "b", "c"), vm.ids())
    }

    @Test
    fun withNothingSelectedTheToolsActOnTheClipUnderThePlayhead() = editorTest { vm ->
        vm.seek(5_000_000L, exact = true) // inside b
        assertEquals(1, vm.targetIndex())
        vm.deleteTarget()
        assertEquals(listOf("a", "c"), vm.ids())
    }

    @Test
    fun selectingTheSelectedClipAgainDeselectsIt() = editorTest { vm ->
        vm.select("a")
        assertEquals("a", vm.selectedId.value)
        vm.select("a")
        assertNull(vm.selectedId.value)
    }

    @Test
    fun duplicateInsertsACopyRightAfterTheClip() = editorTest { vm ->
        vm.select("a")
        vm.duplicateTarget()
        assertEquals(4, vm.p().clips.size)
        val (a, copy) = vm.p().clips
        assertEquals("a", a.id)
        assertNotEquals("a", copy.id)
        assertEquals(a.copy(id = copy.id), copy)
        assertEquals(16_000_000L, vm.p().durationUs)
    }

    @Test
    fun moveReordersClipsAndUndoPutsThemBack() = editorTest { vm ->
        vm.move(0, 2)
        assertEquals(listOf("b", "c", "a"), vm.ids())
        assertEquals(vm.p().clips, engine.preview.clips)
        vm.undo()
        assertEquals(listOf("a", "b", "c"), vm.ids())
    }

    @Test
    fun addClipsInsertsAtTheGivenPlace() = editorTest { vm ->
        engine.importClipsResult = listOf(sampleClip("new"))
        var added = -1
        vm.addClips(listOf("ref"), at = 1) { added = it }
        runCurrent()
        assertEquals(1, added)
        assertEquals(listOf("a", "new", "b", "c"), vm.ids())
    }

    @Test
    fun importingNothingAddsNoUndoStep() = editorTest { vm ->
        engine.importClipsResult = emptyList()
        var added = -1
        vm.addClips(listOf("ref")) { added = it }
        runCurrent()
        assertEquals(0, added)
        assertFalse(vm.canUndo.value)
        assertEquals(3, vm.p().clips.size)
    }

    @Test
    fun insertGoesToTheNearerEdgeOfTheClipUnderThePlayhead() = editorTest { vm ->
        vm.seek(1_000_000L, exact = true) // first half of a
        assertEquals(0, vm.insertIndexAtPlayhead())
        vm.seek(3_000_000L, exact = true) // second half of a
        assertEquals(1, vm.insertIndexAtPlayhead())
    }

    @Test
    fun blankFrameIsAddedAtThePlayheadAndSelected() = editorTest { vm ->
        engine.blankClip = sampleClip("blank").copy(hasAudio = false)
        vm.seek(3_000_000L, exact = true)
        var ok = false
        vm.addBlank { ok = it }
        runCurrent()
        assertTrue(ok)
        assertEquals(listOf("a", "blank", "b", "c"), vm.ids())
        assertEquals("blank", vm.selectedId.value)
    }

    @Test
    fun blankFrameThatCannotBeMadeReportsFalse() = editorTest { vm ->
        engine.blankClip = null
        var ok = true
        vm.addBlank { ok = it }
        runCurrent()
        assertFalse(ok)
        assertEquals(3, vm.p().clips.size)
    }

    @Test
    fun muteTogglesTheOriginalSoundInThePreview() = editorTest { vm ->
        assertFalse(engine.preview.mutedNow)
        vm.toggleMuteOriginal()
        assertTrue(vm.p().muteOriginal)
        assertTrue(engine.preview.mutedNow)
    }

    // ---------- Playback ----------

    @Test
    fun seekStaysInsideTheVideo() = editorTest { vm ->
        vm.seek(99_000_000L, exact = true)
        assertEquals(12_000_000L, vm.playheadUs.value)
        vm.seek(-5L, exact = true)
        assertEquals(0L, vm.playheadUs.value)
    }

    @Test
    fun playingFromTheEndStartsOver() = editorTest { vm ->
        vm.seek(12_000_000L, exact = true)
        vm.togglePlay()
        assertTrue(engine.preview.isPlaying)
        assertEquals(0L, vm.playheadUs.value)
        vm.togglePlay()
        assertFalse(engine.preview.isPlaying)
    }

    @Test
    fun theTickerFollowsThePreviewWhilePlaying() = editorTest { vm ->
        engine.preview.play()
        engine.preview.positionUs = 3_300_000L
        advanceTimeBy(100)
        runCurrent()
        assertEquals(3_300_000L, vm.playheadUs.value)
        assertTrue(vm.isPlaying.value)
    }

    @Test
    fun anEmptyProjectCannotBePlayed() = editorTest(initial = project(emptyList())) { vm ->
        vm.togglePlay()
        assertFalse(engine.preview.isPlaying)
    }

    // ---------- Trim ----------

    @Test
    fun trimIsClampedAndAppliedAsOneUndoStep() = editorTest { vm ->
        vm.select("a")
        vm.startTrim()
        assertEquals(Mode.Trim, vm.mode.value)
        assertEquals("a", engine.preview.sourceClip?.id)

        vm.setTrim(1_000_000L, 99_000_000L, seekToStart = false, exact = true)
        assertEquals(1_000_000L to 4_000_000L, vm.trimRange.value) // end held at the file's length
        vm.setTrim(1_000_000L, 3_000_000L, seekToStart = true, exact = true)
        vm.finishTrim(apply = true)

        assertEquals(Mode.Edit, vm.mode.value)
        val a = vm.p().clips[0]
        assertEquals(1_000_000L, a.trimStartUs)
        assertEquals(3_000_000L, a.trimEndUs)
        assertEquals(10_000_000L, vm.p().durationUs)
        assertEquals(vm.p().clips, engine.preview.exitedWith)

        vm.undo()
        assertEquals(0L, vm.p().clips[0].trimStartUs)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun cancellingTrimChangesNothing() = editorTest { vm ->
        vm.select("a")
        vm.startTrim()
        vm.setTrim(1_000_000L, 2_000_000L, seekToStart = true, exact = true)
        vm.finishTrim(apply = false)
        assertEquals(Mode.Edit, vm.mode.value)
        assertEquals(threeClips(), vm.p().clips)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun trimOnAPhotoOpensItsLengthPanelInstead() = editorTest(initial = project(listOf(sampleClip("pic").copy(kind = com.reelcut.app.data.ClipKind.PHOTO)))) { vm ->
        vm.select("pic")
        vm.startTrim()
        assertEquals(Mode.Duration, vm.mode.value)
    }

    // ---------- Panels (change live, count only on Done) ----------

    @Test
    fun panelChangesShowAtOnceButAreNotUndoStepsOrSavedUntilDone() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Speed)
        vm.setSpeed(2f)
        assertEquals(2f, vm.p().clips[0].speed)
        assertEquals(10_000_000L, vm.p().durationUs)
        assertEquals(vm.p().clips, engine.preview.clips)
        assertFalse(vm.canUndo.value)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, store.writes)
    }

    @Test
    fun doneKeepsAllPanelChangesAsASingleUndoStep() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Speed)
        vm.setSpeed(2f)
        vm.setVolume(1.5f)
        vm.setSpeed(3f)
        vm.closePanel(apply = true)

        assertEquals(Mode.Edit, vm.mode.value)
        assertEquals(3f, vm.p().clips[0].speed)
        assertEquals(1.5f, vm.p().clips[0].volume)

        vm.undo()
        assertEquals(1f, vm.p().clips[0].speed)
        assertEquals(1f, vm.p().clips[0].volume)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun cancelRestoresTheProjectAndLeavesNoUndoStep() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Speed)
        vm.setSpeed(4f)
        vm.closePanel(apply = false)

        assertEquals(threeClips(), vm.p().clips)
        assertEquals(threeClips(), engine.preview.clips)
        assertFalse(vm.canUndo.value)
        assertEquals(Mode.Edit, vm.mode.value)
    }

    @Test
    fun speedVolumeAndPhotoLengthAreHeldInsideTheirLimits() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Speed)
        vm.setSpeed(0.01f)
        assertEquals(Clip.MIN_SPEED, vm.p().clips[0].speed)
        vm.setSpeed(40f)
        assertEquals(Clip.MAX_SPEED, vm.p().clips[0].speed)
        vm.setVolume(9f)
        assertEquals(Clip.MAX_VOLUME, vm.p().clips[0].volume)
        vm.setVolume(-1f)
        assertEquals(0f, vm.p().clips[0].volume)
    }

    @Test
    fun rotateCyclesThroughFourQuarterTurns() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Transform)
        val seen = List(4) { vm.rotate(); vm.p().clips[0].rotation }
        assertEquals(listOf(90, 180, 270, 0), seen)
        vm.flip()
        assertTrue(vm.p().clips[0].flipH)
        vm.setFill(true)
        assertTrue(vm.p().clips[0].fill)
    }

    @Test
    fun applyToAllChangesEveryClip() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Volume)
        vm.stageAll { it.copy(volume = 0.5f) }
        vm.closePanel(apply = true)
        assertTrue(vm.p().clips.all { it.volume == 0.5f })
    }

    @Test
    fun aPanelWithoutAClipDoesNotOpenExceptCanvas() = editorTest(initial = project(emptyList())) { vm ->
        vm.openPanel(Mode.Speed)
        assertEquals(Mode.Edit, vm.mode.value)
        vm.openPanel(Mode.Canvas)
        assertEquals(Mode.Canvas, vm.mode.value)
    }

    // ---------- Filters ----------

    @Test
    fun aFilterIsAPanelEditThatCountsOnceOnDone() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Filter)
        vm.setPreset(com.reelcut.app.data.LookPresets.byId("warm"))
        vm.setLook(vm.panelClip()!!.look.copy(brightness = 0.2f))
        assertEquals(0.2f, vm.p().clips[0].look.brightness)
        assertNull(vm.p().clips[0].filterId) // adjusted by hand: no longer the preset
        assertEquals(vm.p().clips, engine.preview.clips)
        assertFalse(vm.canUndo.value)

        vm.closePanel(apply = true)
        assertTrue(vm.canUndo.value)
        vm.undo()
        assertTrue(vm.p().clips[0].look.isNeutral)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun aPresetSetsTheLookAndRemembersWhichOne() = editorTest { vm ->
        vm.select("b")
        vm.openPanel(Mode.Filter)
        val mono = com.reelcut.app.data.LookPresets.byId("mono")!!
        vm.setPreset(mono)
        assertEquals(mono.look, vm.p().clips[1].look)
        assertEquals("mono", vm.p().clips[1].filterId)
        vm.setPreset(null)
        assertTrue(vm.p().clips[1].look.isNeutral)
        assertNull(vm.p().clips[1].filterId)
    }

    @Test
    fun sliderValuesAreHeldInsideTheirRange() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Filter)
        vm.setLook(com.reelcut.app.data.Look(brightness = 5f, contrast = -5f))
        assertEquals(1f, vm.p().clips[0].look.brightness)
        assertEquals(-1f, vm.p().clips[0].look.contrast)
    }

    @Test
    fun cancellingAFilterRestoresTheClip() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Filter)
        vm.setPreset(com.reelcut.app.data.LookPresets.byId("noir"))
        vm.closePanel(apply = false)
        assertEquals(threeClips(), vm.p().clips)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun applyToAllGivesEveryClipTheSameFilter() = editorTest { vm ->
        vm.select("a")
        vm.openPanel(Mode.Filter)
        vm.setPreset(com.reelcut.app.data.LookPresets.byId("vivid"))
        val chosen = vm.panelClip()!!
        vm.stageAll { c -> c.copy(look = chosen.look, filterId = chosen.filterId) }
        vm.closePanel(apply = true)
        assertTrue(vm.p().clips.all { it.filterId == "vivid" && it.look == chosen.look })
    }

    @Test
    fun theFilterPanelNeedsAClip() = editorTest(initial = project(emptyList())) { vm ->
        vm.openPanel(Mode.Filter)
        assertEquals(Mode.Edit, vm.mode.value)
    }

    // ---------- Autosave ----------

    @Test
    fun autosaveWaitsHalfASecondAfterTheLastEditAndWritesOnce() = editorTest { vm ->
        vm.rename("A")
        advanceTimeBy(499)
        assertEquals(0, store.writes)

        vm.rename("B") // restarts the wait
        advanceTimeBy(499)
        assertEquals(0, store.writes)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, store.writes)
        assertEquals("B", repo.load("p")!!.name)
    }

    @Test
    fun aFailedAutosaveIsReportedAndTheNextGoodOneClearsIt() = editorTest { vm ->
        store.failWrites = true
        vm.rename("A")
        advanceTimeBy(600)
        runCurrent()
        assertTrue(vm.saveFailed.value)
        assertEquals("A", vm.p().name) // the edit is still there

        store.failWrites = false
        vm.rename("B")
        advanceTimeBy(600)
        runCurrent()
        assertFalse(vm.saveFailed.value)
        assertEquals("B", repo.load("p")!!.name)
    }

    // ---------- Text and logos ----------

    @Test
    fun anEmptyNewTextBoxIsDroppedOnDone() = editorTest { vm ->
        vm.addText()
        assertEquals(Mode.Text, vm.mode.value)
        assertTrue(vm.isNewOverlay)
        assertEquals(1, vm.p().overlays.size)

        vm.closePanel(apply = true)
        assertTrue(vm.p().overlays.isEmpty())
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun aTextBoxWithTextIsKeptAsOneUndoStep() = editorTest { vm ->
        vm.addText()
        vm.stageSelectedOverlay { it.copy(text = "Hello") }
        vm.closePanel(apply = true)

        val o = vm.p().overlays.single()
        assertEquals("Hello", o.text)
        assertEquals(OverlayKind.TEXT, o.kind)
        assertEquals(0L, o.startUs)
        assertEquals(3_000_000L, o.endUs)

        vm.undo()
        assertTrue(vm.p().overlays.isEmpty())
    }

    @Test
    fun cancellingANewTextBoxRemovesIt() = editorTest { vm ->
        vm.addText()
        vm.stageSelectedOverlay { it.copy(text = "Hello") }
        vm.closePanel(apply = false)
        assertTrue(vm.p().overlays.isEmpty())
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun anOverlaysTimeRangeIsKeptInsideTheVideo() = editorTest { vm ->
        vm.addText()
        vm.stageSelectedOverlay { it.copy(startUs = 20_000_000L, endUs = 25_000_000L) }
        val o = vm.p().overlays.single()
        assertEquals(11_900_000L, o.startUs)
        assertEquals(12_000_000L, o.endUs)
    }

    @Test
    fun aTextAnimationIsPartOfTheSameUndoStepAsTheRestOfTheTextEdit() = editorTest { vm ->
        vm.addText()
        vm.stageSelectedOverlay { it.copy(text = "Hi", animIn = com.reelcut.app.data.OverlayAnim.POP, animOut = com.reelcut.app.data.OverlayAnim.FADE) }
        vm.closePanel(apply = true)
        val o = vm.p().overlays.single()
        assertEquals(com.reelcut.app.data.OverlayAnim.POP, o.animIn)
        assertEquals(com.reelcut.app.data.OverlayAnim.FADE, o.animOut)
        vm.undo()
        assertTrue(vm.p().overlays.isEmpty())
    }

    @Test
    fun textCannotBeAddedToAnEmptyProject() = editorTest(initial = project(emptyList())) { vm ->
        vm.addText()
        assertEquals(Mode.Edit, vm.mode.value)
        assertTrue(vm.p().overlays.isEmpty())
    }

    @Test
    fun deletingTheOpenOverlayRemovesItAsOneUndoStep() = editorTest { vm ->
        vm.addText()
        vm.stageSelectedOverlay { it.copy(text = "Keep me") }
        vm.closePanel(apply = true)
        val o = vm.p().overlays.single()

        vm.editOverlay(o)
        assertEquals(Mode.Text, vm.mode.value)
        assertFalse(vm.isNewOverlay)
        vm.deleteOverlay()
        assertTrue(vm.p().overlays.isEmpty())
        assertEquals(Mode.Edit, vm.mode.value)

        vm.undo()
        assertEquals("Keep me", vm.p().overlays.single().text)
    }

    @Test
    fun aLogoFromAPictureIsAddedOverTheWholeVideo() = editorTest { vm ->
        engine.importClipsResult = listOf(
            sampleClip("img").copy(kind = com.reelcut.app.data.ClipKind.PHOTO, width = 300, height = 150),
        )
        var failed = false
        vm.addLogo(listOf("pic")) { failed = true }
        runCurrent()
        assertFalse(failed)
        assertEquals(Mode.Logo, vm.mode.value)
        val logo = vm.p().overlays.single()
        assertEquals(OverlayKind.LOGO, logo.kind)
        assertEquals(0L, logo.startUs)
        assertEquals(12_000_000L, logo.endUs)
        assertEquals(300, logo.imageWidth)
    }

    @Test
    fun aLogoFromSomethingThatIsNotAPictureFails() = editorTest { vm ->
        engine.importClipsResult = listOf(sampleClip("video")) // a video, not a photo
        var failed = false
        vm.addLogo(listOf("vid")) { failed = true }
        runCurrent()
        assertTrue(failed)
        assertTrue(vm.p().overlays.isEmpty())
        assertEquals(Mode.Edit, vm.mode.value)
    }

    // ---------- Audio ----------

    private fun music(id: String, uri: String = "content://audio/$id", sourceUs: Long = 20_000_000L) = AudioClip(
        id = id, sourceUri = uri, sourceDurationUs = sourceUs, trimEndUs = sourceUs, name = id,
    )

    @Test
    fun musicIsPlacedAtThePlayheadAndOnlyTheAudibleStretchGoesToThePreview() = editorTest { vm ->
        engine.importAudioResult = listOf(music("m"))
        vm.seek(3_000_000L, exact = true)
        vm.openAudio()
        var added = -1
        vm.addAudio(listOf("song")) { added = it }
        runCurrent()

        assertEquals(1, added)
        assertEquals(Mode.Audio, vm.mode.value)
        assertEquals("m", vm.selectedAudioId.value)
        assertEquals(3_000_000L, vm.p().audio.single().startUs)
        // The 20 s file runs past the 12 s video, so the preview plays only up to the end.
        assertEquals(12_000_000L, engine.preview.audioNow.single().endUs)
    }

    @Test
    fun severalSoundsGoOneAfterAnother() = editorTest { vm ->
        engine.importAudioResult = listOf(music("m1", sourceUs = 2_000_000L), music("m2", sourceUs = 2_000_000L))
        vm.openAudio()
        vm.addAudio(listOf("a", "b")) {}
        runCurrent()
        assertEquals(listOf(0L, 2_000_000L), vm.p().audio.map { it.startUs })
    }

    @Test
    fun audioEditsAreHeldInsideTheirLimitsAndCanBeDeleted() = editorTest { vm ->
        engine.importAudioResult = listOf(music("m"))
        vm.openAudio()
        vm.addAudio(listOf("song")) {}
        runCurrent()

        vm.stageSelectedAudio { it.copy(volume = 5f, fadeInUs = 99_000_000L) }
        val m = vm.p().audio.single()
        assertEquals(AudioClip.MAX_VOLUME, m.volume)
        assertTrue(m.fadeInUs <= AudioClip.MAX_FADE_US)

        vm.deleteSelectedAudio()
        assertTrue(vm.p().audio.isEmpty())
        assertNull(vm.selectedAudioId.value)
    }

    @Test
    fun audioAddedInThePanelIsOneUndoStepOnDone() = editorTest { vm ->
        engine.importAudioResult = listOf(music("m"))
        vm.openAudio()
        vm.addAudio(listOf("song")) {}
        runCurrent()
        vm.closePanel(apply = true)
        assertEquals(1, vm.p().audio.size)
        vm.undo()
        assertTrue(vm.p().audio.isEmpty())
    }

    @Test
    fun audioCannotBeAddedToAnEmptyProject() = editorTest(initial = project(emptyList())) { vm ->
        vm.openAudio()
        assertEquals(Mode.Edit, vm.mode.value)
    }

    // ---------- Extract sound / stickers ----------

    @Test
    fun extractingSoundMakesItItsOwnSoundAndSilencesTheClip() = editorTest(
        initial = project(listOf(sampleClip("a"), sampleClip("b", durationUs = 6_000_000L, trimStartUs = 1_000_000L, trimEndUs = 5_000_000L).copy(volume = 1.5f))),
    ) { vm ->
        vm.select("b")
        assertTrue(vm.canExtractAudio())
        assertTrue(vm.extractAudio())

        assertEquals(0f, vm.p().clips[1].volume)
        val sound = vm.p().audio.single()
        assertEquals("content://media/b", sound.sourceUri)
        assertEquals(AudioKind.MUSIC, sound.kind)
        assertEquals(4_000_000L, sound.startUs) // where clip b starts on the timeline
        assertEquals(1_000_000L, sound.trimStartUs)
        assertEquals(5_000_000L, sound.trimEndUs)
        assertEquals(1.5f, sound.volume)
        assertEquals(sound.durationUs, vm.p().clips[1].durationUs)

        // One step brings back the clip's sound and removes the extracted one.
        vm.undo()
        assertTrue(vm.p().audio.isEmpty())
        assertEquals(1.5f, vm.p().clips[1].volume)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun extractingActsOnTheClipUnderThePlayheadWhenNothingIsSelected() = editorTest { vm ->
        vm.seek(9_000_000L, exact = true) // inside c
        assertTrue(vm.extractAudio())
        assertEquals(8_000_000L, vm.p().audio.single().startUs)
        assertEquals(0f, vm.p().clips[2].volume)
        assertEquals(1f, vm.p().clips[0].volume)
    }

    @Test
    fun soundCannotBeExtractedFromPhotosSilentClipsMutedClipsOrSpedUpClips() {
        val clips = listOf(
            sampleClip("photo").copy(kind = com.reelcut.app.data.ClipKind.PHOTO, hasAudio = false),
            sampleClip("silent").copy(hasAudio = false),
            sampleClip("muted").copy(volume = 0f),
            sampleClip("fast").copy(speed = 2f),
        )
        editorTest(initial = project(clips)) { vm ->
            for (id in listOf("photo", "silent", "muted", "fast")) {
                vm.select(id)
                assertFalse(vm.canExtractAudio(), id)
                assertFalse(vm.extractAudio(), id)
                vm.deselect()
            }
            assertTrue(vm.p().audio.isEmpty())
            assertEquals(clips, vm.p().clips)
            assertFalse(vm.canUndo.value)
        }
    }

    @Test
    fun aStickerIsABigEmojiTextOpenedForEditing() = editorTest { vm ->
        vm.seek(2_000_000L, exact = true)
        vm.addSticker("🔥")

        assertEquals(Mode.Text, vm.mode.value)
        assertTrue(vm.isNewOverlay)
        val o = vm.p().overlays.single()
        assertEquals(OverlayKind.TEXT, o.kind)
        assertEquals("🔥", o.text)
        assertEquals(com.reelcut.app.data.Overlay.STICKER_SCALE, o.scale)
        assertEquals(2_000_000L, o.startUs)
        assertEquals(5_000_000L, o.endUs)

        vm.closePanel(apply = true)
        assertEquals("🔥", vm.p().overlays.single().text) // an emoji is not an "empty" text box
        vm.undo()
        assertTrue(vm.p().overlays.isEmpty())
    }

    @Test
    fun stickersNeedAVideoAndAnEmoji() = editorTest { vm ->
        vm.addSticker("  ")
        assertEquals(Mode.Edit, vm.mode.value)
        assertTrue(vm.p().overlays.isEmpty())
    }

    @Test
    fun stickersCannotBeAddedToAnEmptyProject() = editorTest(initial = project(emptyList())) { vm ->
        vm.addSticker("🔥")
        assertEquals(Mode.Edit, vm.mode.value)
        assertTrue(vm.p().overlays.isEmpty())
    }

    // ---------- Voice-over ----------

    private fun voice() = AudioClip(
        id = "v", kind = AudioKind.VOICE, sourceUri = "file:///v.m4a", sourceDurationUs = 3_000_000L, trimEndUs = 3_000_000L,
    )

    @Test
    fun recordingFailsCleanlyWhenTheMicrophoneCannotBeOpened() = editorTest { vm ->
        engine.recorder.startOk = false
        assertFalse(vm.startRecording())
        assertNull(vm.recordingUs.value)
        assertFalse(engine.preview.isPlaying)
    }

    @Test
    fun aVoiceOverPlaysTheVideoSilentlyAndLandsWhereRecordingBegan() = editorTest { vm ->
        engine.recorder.result = voice()
        vm.seek(2_000_000L, exact = true)

        assertTrue(vm.startRecording())
        assertEquals(0L, vm.recordingUs.value)
        assertTrue(engine.preview.isPlaying)
        assertTrue(engine.preview.mutedNow)
        assertTrue(engine.preview.audioNow.isEmpty())

        // The video plays on while recording; the voice still belongs where recording began.
        engine.preview.positionUs = 7_000_000L
        engine.recorder.elapsedUs = 5_000_000L
        advanceTimeBy(100)
        runCurrent()
        assertEquals(7_000_000L, vm.playheadUs.value)

        var saved = false
        vm.stopRecording { saved = it }
        runCurrent()
        assertTrue(saved)
        assertEquals("Voice 1", engine.recorder.stoppedWithName)
        assertNull(vm.recordingUs.value)
        val clip = vm.p().audio.single()
        assertEquals(2_000_000L, clip.startUs)
        assertEquals(AudioKind.VOICE, clip.kind)
        assertFalse(engine.preview.mutedNow)
    }

    @Test
    fun aRecordingThatCameOutTooShortAddsNothing() = editorTest { vm ->
        engine.recorder.result = null
        vm.startRecording()
        var saved = true
        vm.stopRecording { saved = it }
        runCurrent()
        assertFalse(saved)
        assertTrue(vm.p().audio.isEmpty())
    }

    @Test
    fun cancellingARecordingDiscardsItAndRestoresTheSound() = editorTest { vm ->
        vm.startRecording()
        vm.cancelRecording()
        assertTrue(engine.recorder.cancelled)
        assertNull(vm.recordingUs.value)
        assertFalse(engine.preview.isPlaying)
        assertTrue(vm.p().audio.isEmpty())
    }

    @Test
    fun leavingThePanelWhileRecordingCancelsTheRecording() = editorTest { vm ->
        vm.openAudio()
        vm.startRecording()
        vm.closePanel(apply = true)
        assertTrue(engine.recorder.cancelled)
        assertNull(vm.recordingUs.value)
    }

    @Test
    fun recordingFromTheEndOfTheVideoStartsAtTheBeginning() = editorTest { vm ->
        vm.seek(12_000_000L, exact = true)
        assertTrue(vm.startRecording())
        assertEquals(0L, vm.playheadUs.value)
    }

    @Test
    fun theNextVoiceOverIsNumberedAfterTheOnesAlreadyThere() = editorTest(
        initial = project().copy(audio = listOf(voice().copy(id = "old"))),
    ) { vm ->
        engine.recorder.result = voice()
        vm.startRecording()
        vm.stopRecording()
        runCurrent()
        assertEquals("Voice 2", engine.recorder.stoppedWithName)
    }

    // ---------- Export ----------

    private val settings720 = ExportSettings(height = 720, fps = 30, quality = Quality.LOW)

    @Test
    fun exportRunsThenReportsWhereTheFileWasSaved() = editorTest { vm ->
        engine.exportGate = CompletableDeferred()
        vm.startExport(settings720) { _, _, _ -> emptyList() }
        assertEquals(ExportState.Running(0), vm.export.value)

        engine.exportStarted.await()
        vm.export.first { it == ExportState.Running(10) }
        assertEquals(settings720, engine.exportedSettings)
        assertEquals(settings720, vm.p().export)
        assertEquals(listOf("a", "b", "c"), engine.exportedProject!!.clips.map { it.id })

        engine.exportGate!!.complete(Unit)
        assertEquals(ExportState.Done(engine.exportRef), vm.export.first { it is ExportState.Done })
    }

    @Test
    fun aFailedExportShowsTheReason() = editorTest { vm ->
        engine.exportError = ExportFailed("The phone is out of space.")
        vm.startExport(settings720) { _, _, _ -> emptyList() }
        assertEquals(
            ExportState.Failed("The phone is out of space."),
            vm.export.first { it is ExportState.Failed },
        )
        assertEquals(threeClips(), vm.p().clips) // the project is untouched
    }

    @Test
    fun anUnexpectedExportErrorGetsAPlainMessage() = editorTest { vm ->
        engine.exportError = IllegalStateException()
        vm.startExport(settings720) { _, _, _ -> emptyList() }
        assertEquals(ExportState.Failed("Export failed."), vm.export.first { it is ExportState.Failed })
    }

    @Test
    fun cancellingStopsTheEngineAndReturnsToIdle() = editorTest { vm ->
        engine.exportGate = CompletableDeferred()
        vm.startExport(settings720) { _, _, _ -> emptyList() }
        engine.exportStarted.await()

        vm.cancelExport()
        engine.exportCancelled.await()
        assertEquals(ExportState.Idle, vm.export.value)
    }

    @Test
    fun startingAnExportWhileOneRunsIsIgnored() = editorTest { vm ->
        engine.exportGate = CompletableDeferred()
        vm.startExport(settings720) { _, _, _ -> emptyList() }
        engine.exportStarted.await()
        vm.export.first { it == ExportState.Running(10) }

        // A second request must change nothing: not the settings, not the running state.
        vm.startExport(ExportSettings(height = 1080, fps = 60, quality = Quality.HIGH)) { _, _, _ -> emptyList() }
        assertEquals(settings720, vm.p().export)
        assertEquals(ExportState.Running(10), vm.export.value)
        assertEquals(1, engine.exportCalls)

        vm.resetExport() // cannot reset a running export
        assertEquals(ExportState.Running(10), vm.export.value)
    }

    @Test
    fun nothingIsExportedFromAnEmptyProject() = editorTest(initial = project(emptyList())) { vm ->
        vm.startExport(settings720) { _, _, _ -> emptyList() }
        assertEquals(ExportState.Idle, vm.export.value)
        assertEquals(0, engine.exportCalls)
    }

    @Test
    fun onlySoundsThatCanStillBeOpenedAreExported() {
        engine.unreadable += "content://audio/gone"
        val withSounds = project().copy(audio = listOf(music("ok"), music("gone")))
        editorTest(initial = withSounds) { vm ->
            vm.startExport(settings720) { _, _, _ -> emptyList() }
            engine.exportStarted.await()
            assertEquals(listOf("ok"), engine.exportedProject!!.audio.map { it.id })
        }
    }

    @Test
    fun aFinishedExportCanBeResetToIdle() = editorTest { vm ->
        vm.startExport(settings720) { _, _, _ -> emptyList() }
        vm.export.first { it is ExportState.Done }
        vm.resetExport()
        assertEquals(ExportState.Idle, vm.export.value)
    }
}
