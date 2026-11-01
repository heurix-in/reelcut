package com.reelcut.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.reelcut.app.desktop.Ffmpeg
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual val UrbanistFamily: FontFamily = FontFamily(
    Font("font/urbanist_light.ttf", FontWeight.Light),
    Font("font/urbanist_regular.ttf", FontWeight.Normal),
    Font("font/urbanist_medium.ttf", FontWeight.Medium),
    Font("font/urbanist_semibold.ttf", FontWeight.SemiBold),
    Font("font/urbanist_bold.ttf", FontWeight.Bold),
)

actual val isPhone: Boolean = false

actual val platformSupportsBlur: Boolean = true

/** The Windows file picker (several files at once). */
@Composable
actual fun rememberMediaPicker(onPicked: (List<String>) -> Unit): (MediaFilter) -> Unit = remember {
    { filter: MediaFilter ->
        val dialog = FileDialog(null as Frame?, "Add videos or photos", FileDialog.LOAD).apply {
            isMultipleMode = true
            val exts = when (filter) {
                MediaFilter.All -> Ffmpeg.mediaExtensions
                MediaFilter.Video -> Ffmpeg.videoExtensions
                MediaFilter.Photo -> Ffmpeg.mediaExtensions - Ffmpeg.videoExtensions
            }
            // Windows uses the file pattern; other systems use the filter.
            file = exts.joinToString(";") { "*.$it" }
            setFilenameFilter { _, name -> File(name).extension.lowercase() in exts }
        }
        dialog.isVisible = true
        val picked = dialog.files?.map { it.absolutePath }.orEmpty()
            .filter { File(it).extension.lowercase() in Ffmpeg.mediaExtensions }
        dialog.dispose()
        onPicked(picked)
    }
}

/** The Windows file picker for music and other sound files. */
@Composable
actual fun rememberAudioPicker(onPicked: (List<String>) -> Unit): () -> Unit = remember {
    {
        val exts = Ffmpeg.audioExtensions
        val dialog = FileDialog(null as Frame?, "Add music or sound", FileDialog.LOAD).apply {
            isMultipleMode = true
            file = exts.joinToString(";") { "*.$it" }
            setFilenameFilter { _, name -> File(name).extension.lowercase() in exts }
        }
        dialog.isVisible = true
        val picked = dialog.files?.map { it.absolutePath }.orEmpty()
            .filter { File(it).extension.lowercase() in exts }
        dialog.dispose()
        onPicked(picked)
    }
}

/** Windows asks for microphone access itself the first time one is opened. */
@Composable
actual fun rememberMicPermission(): (onGranted: () -> Unit) -> Unit = remember { { onGranted: () -> Unit -> onGranted() } }

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun KeepScreenOn() = Unit

@Composable
actual fun rememberSavePermission(): (onGranted: () -> Unit) -> Unit = remember { { onGranted: () -> Unit -> onGranted() } }
