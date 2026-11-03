package com.reelcut.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelcut.app.UrbanistFamily

/**
 * Warm near-black with one gold accent. Surfaces are "glass": a translucent light gradient over a
 * dark scrim with a hairline edge (see Glass.kt). Gold is always a fill with dark content on it.
 */
object Palette {
    val Ink = Color(0xFF1B1915)
    val InkDeep = Color(0xFF100F0D)
    val InkRaised = Color(0xFF26231E)
    val Accent = Color(0xFFD4AF5A)
    val AccentLight = Color(0xFFEAD08C)
    val AccentDeep = Color(0xFFB38B3A)
    val Stone = Color(0xFF5C574D)
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFF3ECD8)
    val Glass = Color(0x99181510)       // flat translucent dark, for small badges over media
    val GlassStrong = Color(0xCC14120E)
    val Muted = Color(0xA6FFFFFF)
    val Danger = Color(0xFFE8806E)
    val Line = Color(0x2EFFFFFF)

    // Glass surface parts
    val GlassScrim = Color(0x59000000)          // keeps text readable over bright video
    val GlassScrimStrong = Color(0x80000000)
    val GlassTop = Color(0x2BFFFFFF)
    val GlassBottom = Color(0x0FFFFFFF)
    val GlassTopStrong = Color(0x38FFFFFF)
    val GlassBottomStrong = Color(0x14FFFFFF)
    val GlassEdgeHi = Color(0x52FFFFFF)
    val GlassEdgeLo = Color(0x0FFFFFFF)

    // Darkening over pictures and video
    val Scrim = Color(0x99000000)
    val ScrimStrong = Color(0xCC000000)

    // Screen background and the soft fade at the edge of the timeline
    val BgTop = Color(0xFF16140F)
    val BgBottom = Color(0xFF0C0B09)
    val SheetFade = Color(0xF2171613)

    /** Gold fill for buttons, the active tool and selected chips. */
    val AccentBrush: Brush = Brush.verticalGradient(listOf(AccentLight, Accent))
}

val Urbanist: FontFamily get() = UrbanistFamily

object Type {
    val Headline = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.Light, fontSize = 34.sp, lineHeight = 38.sp, color = Palette.White)
    val Title = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 24.sp, color = Palette.White)
    val Body = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = Palette.White)
    val Label = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, color = Palette.White)
    val Small = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp, color = Palette.White)
}

object Shape {
    val Card = RoundedCornerShape(28.dp)
    val Thumb = RoundedCornerShape(20.dp)
    val Sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val Pill = RoundedCornerShape(50)
    val ClipPill = RoundedCornerShape(14.dp)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Ink,
    secondary = Palette.Stone,
    onSecondary = Palette.White,
    background = Palette.Ink,
    onBackground = Palette.White,
    surface = Palette.Ink,
    onSurface = Palette.White,
    surfaceVariant = Palette.InkRaised,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerHigh = Palette.InkRaised,
    surfaceContainerLow = Palette.Ink,
    outline = Palette.Stone,
    error = Palette.Danger,
)

private val typography = Typography(
    headlineMedium = Type.Headline,
    titleMedium = Type.Title,
    titleLarge = Type.Title.copy(fontSize = 22.sp),
    bodyMedium = Type.Body,
    bodyLarge = Type.Body.copy(fontSize = 16.sp),
    labelLarge = Type.Label.copy(fontSize = 14.sp),
    labelMedium = Type.Label,
)

@Composable
fun ReelcutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
