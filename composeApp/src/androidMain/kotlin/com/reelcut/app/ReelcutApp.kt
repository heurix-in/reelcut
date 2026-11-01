package com.reelcut.app

import android.app.Application
import com.reelcut.app.data.ProjectRepository
import com.reelcut.app.engine.Services
import com.reelcut.app.media.AndroidMediaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class ReelcutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Services.current = Services(
            repository = ProjectRepository(FileProjectStore(File(filesDir, "projects"))),
            engine = AndroidMediaEngine(this),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
    }
}
