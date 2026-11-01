package com.reelcut.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Type

/** One labelled tool in the bottom toolbar. */
data class Tool(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    /** Planned for a later update: shown with a "Soon" tag. */
    val soon: Boolean = false,
    /** Drawn with a yellow circle (an option that is switched on). */
    val active: Boolean = false,
    val onClick: () -> Unit,
)

/** InShot-style row of labelled tools that scrolls sideways. */
@Composable
fun ToolBar(
    tools: List<Tool>,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(78.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.padding(start = 10.dp, end = 4.dp)) { leading() }
        }
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tools.forEach { ToolItem(it) }
        }
    }
}

@Composable
private fun ToolItem(tool: Tool) {
    val dim = !tool.enabled || tool.soon
    Column(
        Modifier
            .width(66.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = tool.enabled, role = Role.Button, onClick = tool.onClick)
            .padding(vertical = 6.dp)
            .alpha(if (!tool.enabled) 0.35f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (tool.active) Palette.Accent else Palette.InkRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tool.icon,
                    contentDescription = tool.label,
                    tint = when {
                        tool.active -> Palette.Ink
                        dim -> Palette.Muted
                        else -> Palette.White
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            if (tool.soon) {
                Text(
                    "SOON",
                    style = Type.Small.copy(fontSize = 7.sp, lineHeight = 8.sp),
                    color = Palette.Ink,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-2).dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Accent)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            tool.label,
            style = Type.Label.copy(fontSize = 11.sp),
            color = if (dim) Palette.Muted else Palette.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
