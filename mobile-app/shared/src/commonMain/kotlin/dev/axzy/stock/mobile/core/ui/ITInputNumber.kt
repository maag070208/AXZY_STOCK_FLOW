package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.ITShape

/**
 * A value the number pad writes into: its name, and the number typed so far. The selected tile is the one the
 * pad is writing to; tapping a tile selects it. An empty value shows a faint zero, so the tile never looks broken.
 */
@Composable
fun ITInputNumber(
    label: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = when {
        isError -> colors.error
        selected -> colors.primary
        else -> colors.outlineVariant
    }
    Column(
        modifier = modifier
            .clip(ITShape.Control)
            .background(colors.surface, ITShape.Control)
            .border(if (selected || isError) 2.dp else 1.dp, borderColor, ITShape.Control)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
        )
        Text(
            text = value.ifEmpty { "0" },
            style = MaterialTheme.typography.headlineSmall,
            color = if (value.isEmpty()) colors.onSurfaceVariant.copy(alpha = 0.45f) else colors.onSurface,
        )
    }
}
