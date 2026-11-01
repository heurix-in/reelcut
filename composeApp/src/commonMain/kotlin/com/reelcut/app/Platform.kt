package com.reelcut.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** Small things each platform provides. JDK-based ones live in src/jvmShared, used by both. */

expect fun newId(): String

expect fun nowMillis(): Long

/** java.text.SimpleDateFormat-style pattern, in the device's language. */
expect fun formatDate(millis: Long, pattern: String): String

/** The Urbanist font in the weights the app uses. */
expect val UrbanistFamily: FontFamily

enum class MediaFilter { All, Video, Photo }

/** Returns a function that opens the system picker; [onPicked] gets the chosen files. */
@Composable
expect fun rememberMediaPicker(onPicked: (List<String>) -> Unit): (MediaFilter) -> Unit

/** System back (Android). Does nothing on Windows. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Keeps the screen awake while shown (Android). */
@Composable
expect fun KeepScreenOn()

/**
 * Returns a function that runs its argument once saving videos is allowed
 * (asks for storage permission on Android 8–9; runs straight away elsewhere).
 */
@Composable
expect fun rememberSavePermission(): (onGranted: () -> Unit) -> Unit

/** True on a phone (touch, portrait). Desktop shows a few extra hints such as mouse-wheel zoom. */
expect val isPhone: Boolean

/** True where Modifier.blur works (Android 12+ and Windows). */
expect val platformSupportsBlur: Boolean
