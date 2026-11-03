package com.reelcut.app.platform

import com.reelcut.app.VoiceFolder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceFolderTest {
    private val root: File = createTempDirectory("reelcut-voice").toFile()
    private val voiceDir = File(root, "voice").apply { mkdirs() }
    private val folder = VoiceFolder(voiceDir)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun deletesARecordingGivenByPath() {
        val f = File(voiceDir, "a.wav").apply { writeText("x") }
        folder.delete(f.absolutePath)
        assertFalse(f.exists())
    }

    @Test
    fun deletesARecordingGivenAsAFileUri() {
        val f = File(voiceDir, "b.m4a").apply { writeText("x") }
        folder.delete(f.toURI().toString())
        assertFalse(f.exists())
    }

    @Test
    fun leavesFilesOutsideTheVoiceFolderAlone() {
        val outside = File(root, "holiday.mp4").apply { writeText("precious") }
        folder.delete(outside.absolutePath)
        assertTrue(outside.exists())
    }

    @Test
    fun cannotBeTrickedIntoLeavingTheFolderWithDotDot() {
        val outside = File(root, "keep.txt").apply { writeText("precious") }
        folder.delete(File(voiceDir, "../keep.txt").path)
        assertTrue(outside.exists())
    }

    @Test
    fun ignoresFilesInSubfoldersAndMissingFiles() {
        val nested = File(voiceDir, "sub").apply { mkdirs() }
        val f = File(nested, "c.wav").apply { writeText("x") }
        folder.delete(f.absolutePath)
        assertTrue(f.exists())
        folder.delete(File(voiceDir, "nothing.wav").absolutePath) // no error
    }
}
