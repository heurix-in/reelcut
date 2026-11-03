package com.reelcut.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape as ComposeShape

/**
 * A frosted-glass surface: a dark scrim (so text stays readable over bright video), a light
 * gradient on top of it, and a hairline edge that is brighter at the top left. Kept quiet on
 * purpose; [strong] is for large panels that carry a lot of controls.
 */
fun Modifier.glass(shape: ComposeShape = Shape.Card, strong: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (strong) Palette.GlassScrimStrong else Palette.GlassScrim)
    .background(
        Brush.verticalGradient(
            listOf(
                if (strong) Palette.GlassTopStrong else Palette.GlassTop,
                if (strong) Palette.GlassBottomStrong else Palette.GlassBottom,
            )
        )
    )
    .border(0.75.dp, Brush.linearGradient(listOf(Palette.GlassEdgeHi, Palette.GlassEdgeLo)), shape)

/** Screen background: a warm dark gradient with two faint gold glows, so glass has something to sit on. */
fun Modifier.appBackground(): Modifier = this
    .background(Brush.verticalGradient(listOf(Palette.BgTop, Palette.BgBottom)))
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(Palette.Accent.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.92f, 0f),
                radius = size.maxDimension * 0.55f,
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.AccentDeep.copy(alpha = 0.07f), Color.Transparent),
                center = Offset(0f, size.height),
                radius = size.maxDimension * 0.5f,
            )
        )
    }
