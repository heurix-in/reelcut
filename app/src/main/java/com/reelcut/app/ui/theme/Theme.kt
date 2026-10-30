package com.reelcut.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelcut.app.R

// Palette from the Gixlo concept board.
object Palette {
    val Ink = Color(0xFF252525)
    val InkDeep = Color(0xFF1C1C1B)
    val InkRaised = Color(0xFF323230)
    val Accent = Color(0xFFEEEA7A)
    val Stone = Color(0xFF5C574D)
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFF4F4D7)
    val Glass = Color(0xB3252525)      // floating controls over video, ~70%
    val GlassStrong = Color(0xD9252525) // timeline strip, ~85%
    val Muted = Color(0x99FFFFFF)
    val Danger = Color(0xFFE8806E)
}

@OptIn(ExperimentalTextApi::class)
private fun urbanist(weight: Int) = Font(
    resId = R.font.urbanist,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Urbanist = FontFamily(urbanist(300), urbanist(400), urbanist(500), urbanist(600), urbanist(700))

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
