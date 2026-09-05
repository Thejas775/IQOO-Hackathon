package com.knoxx.dailyflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkMuted
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.Violet
import com.knoxx.dailyflow.ui.theme.VioletDeep

enum class Tab(val label: String) {
    Today("Today"),
    Calendar("Calendar"),
    Ask("Ask"),
    You("You")
}

/**
 * The five-slot bar: four tabs around a raised add button.
 *
 * The add button is not a tab and never takes the selected state — it opens a
 * sheet over whatever is showing. Making it selectable would imply a fifth
 * destination that does not exist, and would leave the bar looking wrong for
 * as long as the sheet was open.
 *
 * The bar has no solid background of its own. Content scrolls beneath it and
 * fades out behind a gradient, matching the design, so a list never appears to
 * stop dead against an opaque strip.
 */
@Composable
fun BottomNav(
    current: Tab,
    onSelect: (Tab) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.38f to Paper,
                    1f to Paper
                )
            )
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        NavItem(Tab.Today, Icons.Outlined.Home, current, onSelect, Modifier.weight(1f))
        NavItem(Tab.Calendar, Icons.Outlined.CalendarToday, current, onSelect, Modifier.weight(1f))

        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Ink)
                    .flowClickable(onAdd),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add a task", tint = Paper)
            }
        }

        NavItem(Tab.Ask, Icons.Outlined.ChatBubbleOutline, current, onSelect, Modifier.weight(1f))
        NavItem(Tab.You, Icons.Outlined.PersonOutline, current, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun NavItem(
    tab: Tab,
    icon: ImageVector,
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = tab == current
    Column(
        modifier = modifier.flowClickable { onSelect(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            icon,
            contentDescription = tab.label,
            tint = if (selected) Violet else InkMuted,
            modifier = Modifier.size(20.dp)
        )
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) VioletDeep else InkMuted,
            fontWeight = FontWeight(if (selected) 700 else 600)
        )
    }
}
