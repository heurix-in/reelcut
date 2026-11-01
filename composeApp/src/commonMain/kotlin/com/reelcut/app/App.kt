package com.reelcut.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.reelcut.app.engine.Services
import com.reelcut.app.ui.editor.EditorScreen
import com.reelcut.app.ui.home.HomeScreen
import com.reelcut.app.ui.theme.ReelcutTheme

/** The whole app, shared by Android and Windows. */
@Composable
fun App(services: Services = Services.current) {
    ReelcutTheme {
        var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
        val id = openProjectId
        if (id == null) {
            HomeScreen(services, onOpen = { openProjectId = it })
        } else {
            // Each editor visit gets its own ViewModel store, cleared (and the player released) on leaving.
            val owner = remember(id) {
                object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
            }
            DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
            // Registered before the editor's own handler, so the editor gets back presses first.
            PlatformBackHandler { openProjectId = null }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                EditorScreen(services, projectId = id, onBack = { openProjectId = null })
            }
        }
    }
}
