package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.ITShape

/** A message in a tinted band: the result of an action, or why it did not happen. The color says the kind. */
@Composable
fun ITAlert(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .clip(ITShape.Row)
            .background(color.copy(alpha = 0.10f), ITShape.Row)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}
