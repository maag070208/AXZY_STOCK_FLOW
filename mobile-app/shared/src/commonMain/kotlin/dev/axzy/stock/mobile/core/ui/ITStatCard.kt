package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** One number with its name, with no frame: for figures that sit inside a card. The color says what it means. */
@Composable
fun ITStat(
    value: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One number with its name in its own card: the figures at the top of a screen. With [onClick] the card is a
 * filter: tapping it chooses that figure, and [selected] marks the one in force.
 */
@Composable
fun ITStatCard(
    value: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    ITCard(modifier = modifier, onClick = onClick, selected = selected) {
        ITStat(value = value, label = label, color = color)
    }
}
