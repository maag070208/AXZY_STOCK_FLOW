package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.ITShape

/** The quiet action next to a main one: sign out, change the server. Same height and shape as [ITButton]. */
@Composable
fun ITTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = ITShape.Control,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
