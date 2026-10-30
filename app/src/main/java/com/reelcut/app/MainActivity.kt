package com.reelcut.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.reelcut.app.ui.editor.EditorScreen
import com.reelcut.app.ui.home.HomeScreen
import com.reelcut.app.ui.theme.ReelcutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ReelcutTheme { AppRoot() }
        }
    }
}

@Composable
private fun AppRoot() {
    var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val id = openProjectId
    if (id == null) {
        HomeScreen(onOpen = { openProjectId = it })
    } else {
        // Each editor visit gets its own ViewModel store, cleared (and the player released) on leaving.
        val owner = remember(id) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
        BackHandler { openProjectId = null }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            EditorScreen(projectId = id, onBack = { openProjectId = null })
        }
    }
}
