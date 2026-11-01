package com.reelcut.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.reelcut.app.desktop.Ffmpeg
import com.reelcut.app.desktop.NativeFileDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Opens the Windows file window (falls back to Java's older dialog) and hands back the chosen files. */
private fun CoroutineScope.pickFiles(
    title: String,
    filterName: String,
    extensions: Set<String>,
    onPicked: (List<String>) -> Unit,
) {
    launch {
        val native = withContext(Dispatchers.IO) { NativeFileDialog.open(title, filterName, extensions) }
        val picked = native ?: run {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
                isMultipleMode = true
                // Windows uses the file pattern; other systems use the filter.
                file = extensions.joinToString(";") { "*.$it" }
                setFilenameFilter { _, name -> File(name).extension.lowercase() in extensions }
            }
            dialog.isVisible = true
            dialog.files?.map { it.absolutePath }.orEmpty().also { dialog.dispose() }
        }
        onPicked(picked.filter { File(it).extension.lowercase() in extensions })
    }
}

/** The Windows file picker (several files at once). */
@Composable
actual fun rememberMediaPicker(onPicked: (List<String>) -> Unit): (MediaFilter) -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember(scope) {
        { filter: MediaFilter ->
            val (name, exts) = when (filter) {
                MediaFilter.All -> "Videos and photos" to Ffmpeg.mediaExtensions
                MediaFilter.Video -> "Videos" to Ffmpeg.videoExtensions
                MediaFilter.Photo -> "Photos" to (Ffmpeg.mediaExtensions - Ffmpeg.videoExtensions)
            }
            scope.pickFiles("Add videos or photos", name, exts) { latest.value(it) }
        }
    }
}

/** The Windows file picker for music and other sound files. */
@Composable
actual fun rememberAudioPicker(onPicked: (List<String>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember(scope) {
        { scope.pickFiles("Add music or sound", "Sound files", Ffmpeg.audioExtensions) { latest.value(it) } }
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
