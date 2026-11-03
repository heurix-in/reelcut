package com.reelcut.app.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.reelcut.app.App
import com.reelcut.app.FileProjectStore
import com.reelcut.app.VoiceFolder
import com.reelcut.app.data.ProjectRepository
import com.reelcut.app.engine.Services
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Dimension
import java.io.File
import javax.imageio.ImageIO

fun main() {
    if (System.getProperty("org.bytedeco.javacpp.platform.extension") == null) {
        System.setProperty("org.bytedeco.javacpp.platform.extension", "-gpl")
    }
    val dataDir = System.getenv("APPDATA")?.let { File(it, "Reelcut") }
        ?: File(System.getProperty("user.home"), ".reelcut")
    Services.current = Services(
        repository = ProjectRepository(FileProjectStore(File(dataDir, "projects")), VoiceFolder(File(dataDir, "voice"))),
        engine = DesktopMediaEngine(File(dataDir, "voice")),
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )
    // Load FFmpeg in the background so the first import is quick.
    Thread { runCatching { Ffmpeg.init(); Ffmpeg.executable } }.apply { isDaemon = true }.start()

    application {
        val state = rememberWindowState(width = 1280.dp, height = 880.dp)
        val icon = remember {
            Thread.currentThread().contextClassLoader.getResourceAsStream("reelcut_icon.png")
                ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
        }
        Window(onCloseRequest = ::exitApplication, title = "Reelcut", state = state, icon = icon) {
            window.minimumSize = Dimension(480, 700)
            App()
        }
    }
}
