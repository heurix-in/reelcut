package com.reelcut.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.reelcut.app.data.TextFont

actual val UrbanistFamily: FontFamily = FontFamily(
    Font(R.font.urbanist_light, FontWeight.Light),
    Font(R.font.urbanist_regular, FontWeight.Normal),
    Font(R.font.urbanist_medium, FontWeight.Medium),
    Font(R.font.urbanist_semibold, FontWeight.SemiBold),
    Font(R.font.urbanist_bold, FontWeight.Bold),
)

actual val BundledTextFonts: Map<TextFont, FontFamily> = mapOf(
    TextFont.POSTER to FontFamily(Font(R.font.bebas_neue, FontWeight.Normal)),
    TextFont.IMPACT to FontFamily(Font(R.font.anton, FontWeight.Normal)),
    TextFont.ELEGANT to FontFamily(Font(R.font.playfair_display_bold, FontWeight.Normal)),
    TextFont.SCRIPT to FontFamily(Font(R.font.pacifico, FontWeight.Normal)),
    TextFont.HAND to FontFamily(Font(R.font.caveat_bold, FontWeight.Normal)),
    TextFont.ROUND to FontFamily(Font(R.font.fredoka_semibold, FontWeight.Normal)),
)

actual val isPhone: Boolean = true

actual val platformSupportsBlur: Boolean = Build.VERSION.SDK_INT >= 31

@Composable
actual fun rememberMediaPicker(onPicked: (List<String>) -> Unit): (MediaFilter) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        onPicked(uris.map { it.toString() })
    }
    return remember(launcher) {
        { filter: MediaFilter ->
            val type = when (filter) {
                MediaFilter.All -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
                MediaFilter.Video -> ActivityResultContracts.PickVisualMedia.VideoOnly
                MediaFilter.Photo -> ActivityResultContracts.PickVisualMedia.ImageOnly
            }
            launcher.launch(PickVisualMediaRequest(type))
        }
    }
}

@Composable
actual fun rememberAudioPicker(onPicked: (List<String>) -> Unit): () -> Unit {
    // The document picker (not the photo picker) lists sound files; the app keeps read access afterwards.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onPicked(uris.map { it.toString() })
    }
    return remember(launcher) { { launcher.launch(arrayOf("audio/*")) } }
}

@Composable
actual fun rememberMicPermission(): (onGranted: () -> Unit) -> Unit {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) holder[0]?.invoke()
        holder[0] = null
    }
    return remember(launcher) {
        { onGranted: () -> Unit ->
            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (allowed) {
                onGranted()
            } else {
                holder[0] = onGranted
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
actual fun rememberSavePermission(): (onGranted: () -> Unit) -> Unit {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) holder[0]?.invoke()
        holder[0] = null
    }
    return remember(launcher) {
        { onGranted: () -> Unit ->
            val needs = Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (needs) {
                holder[0] = onGranted
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                onGranted()
            }
        }
    }
}
