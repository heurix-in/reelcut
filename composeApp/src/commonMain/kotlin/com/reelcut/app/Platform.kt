package com.reelcut.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.reelcut.app.data.TextFont

/** Small things each platform provides. JDK-based ones live in src/jvmShared, used by both. */

expect fun newId(): String

expect fun nowMillis(): Long

/** java.text.SimpleDateFormat-style pattern, in the device's language. */
expect fun formatDate(millis: Long, pattern: String): String

/** The Urbanist font in the weights the app uses. */
expect val UrbanistFamily: FontFamily

/** The bundled fonts for the text-style choices that are not Urbanist or a system family (same on every platform). */
expect val BundledTextFonts: Map<TextFont, FontFamily>

enum class MediaFilter { All, Video, Photo }

/** Returns a function that opens the system picker; [onPicked] gets the chosen files. */
@Composable
expect fun rememberMediaPicker(onPicked: (List<String>) -> Unit): (MediaFilter) -> Unit

/** Returns a function that opens the system picker for sound files; [onPicked] gets the chosen files. */
@Composable
expect fun rememberAudioPicker(onPicked: (List<String>) -> Unit): () -> Unit

/** Returns a function that runs its argument once the microphone may be used (asks on Android). */
@Composable
expect fun rememberMicPermission(): (onGranted: () -> Unit) -> Unit

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
