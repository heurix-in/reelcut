package com.reelcut.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Shape
import com.reelcut.app.ui.theme.Type
import com.reelcut.app.ui.theme.glass

enum class RoundStyle { Glass, Accent, White, Ink, Stone }

/** The round icon buttons used everywhere in the Gixlo look. */
@Composable
fun RoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RoundStyle = RoundStyle.Glass,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
) {
    val fg = when (style) {
        RoundStyle.Accent, RoundStyle.White -> Palette.Ink
        else -> Palette.White
    }
    val surface = when (style) {
        RoundStyle.Glass, RoundStyle.Stone -> Modifier.glass(CircleShape)
        RoundStyle.Ink -> Modifier.glass(CircleShape, strong = true)
        RoundStyle.Accent -> Modifier.clip(CircleShape).background(Palette.AccentBrush)
        RoundStyle.White -> Modifier.clip(CircleShape).background(Palette.White)
    }
    Box(
        modifier = modifier
            .size(maxOf(size, 48.dp).coerceAtMost(size + 8.dp))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = size / 2),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .then(surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(iconSize))
        }
    }
}

/** A rounded chip / tab. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectedColor: Color = Palette.Accent,
    onClick: (() -> Unit)? = null,
    style: TextStyle = Type.Label,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    leading: ImageVector? = null,
) {
    val fg = if (selected) Palette.Ink else Palette.White
    val surface = if (selected) {
        Modifier.clip(Shape.Pill).background(if (selectedColor == Palette.Accent) Palette.AccentBrush else SolidColor(selectedColor))
    } else {
        Modifier.glass(Shape.Pill)
    }
    Row(
        modifier = modifier
            .then(surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, style = style, color = fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Big gold call-to-action. */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .then(if (enabled) Modifier.clip(Shape.Pill).background(Palette.AccentBrush) else Modifier.glass(Shape.Pill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Title.copy(fontSize = 16.sp), color = if (enabled) Palette.Ink else Palette.Muted)
    }
}

/** Quiet secondary button. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .glass(Shape.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Title.copy(fontSize = 16.sp), color = Palette.White)
    }
}

/** "1:05" or "0:08.4" style durations. */
fun formatTime(us: Long, tenths: Boolean = false): String {
    val totalMs = us.coerceAtLeast(0L) / 1000
    val m = totalMs / 60_000
    val s = (totalMs / 1000) % 60
    val ss = if (s < 10) "0$s" else "$s"
    return if (tenths) "$m:$ss.${(totalMs / 100) % 10}" else "$m:$ss"
}

/** One decimal place, e.g. 2.5 → "2.5", 3.0 → "3". */
fun formatOneDecimal(v: Double): String {
    val tenths = kotlin.math.round(v * 10).toLong()
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}
