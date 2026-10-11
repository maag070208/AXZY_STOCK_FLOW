package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.AppShape

/** The status pill. The color says the state; the label says it in words. */
@Composable
fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier
            .clip(AppShape.Pill)
            .background(color.copy(alpha = 0.12f), AppShape.Pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}
