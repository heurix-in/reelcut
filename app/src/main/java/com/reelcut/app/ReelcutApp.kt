package com.reelcut.app

import android.app.Application
import com.reelcut.app.data.ProjectRepository
import com.reelcut.app.media.ThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class ReelcutApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency container: one instance of each shared service. */
class AppContainer(app: Application) {
    val repository = ProjectRepository(File(app.filesDir, "projects"))
    val thumbnails = ThumbnailCache(app)

    /** For work that must outlive a screen, like the final save when leaving the editor. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}
