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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Shape
import com.reelcut.app.ui.theme.Type

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
    val (bg, fg) = when (style) {
        RoundStyle.Glass -> Palette.Glass to Palette.White
        RoundStyle.Accent -> Palette.Accent to Palette.Ink
        RoundStyle.White -> Palette.White to Palette.Ink
        RoundStyle.Ink -> Palette.Ink to Palette.White
        RoundStyle.Stone -> Palette.Stone to Palette.White
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
                .clip(CircleShape)
                .background(bg),
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
    selectedColor: Color = Palette.White,
    onClick: (() -> Unit)? = null,
    style: TextStyle = Type.Label,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    leading: ImageVector? = null,
) {
    val bg = if (selected) selectedColor else Palette.Glass
    val fg = if (selected) Palette.Ink else Palette.White
    Row(
        modifier = modifier
            .clip(Shape.Pill)
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, style = style, color = fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Big yellow call-to-action. */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .clip(Shape.Pill)
            .background(if (enabled) Palette.Accent else Palette.Stone)
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
            .clip(Shape.Pill)
            .background(Palette.InkRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Title.copy(fontSize = 16.sp), color = Palette.White)
    }
}

/** "1:05" or "0:08.4" style durations. */
fun formatTime(us: Long, tenths: Boolean = false): String {
    val totalMs = us / 1000
    val m = totalMs / 60_000
    val s = (totalMs / 1000) % 60
    return if (tenths) {
        val t = (totalMs / 100) % 10
        "%d:%02d.%d".format(m, s, t)
    } else "%d:%02d".format(m, s)
}
