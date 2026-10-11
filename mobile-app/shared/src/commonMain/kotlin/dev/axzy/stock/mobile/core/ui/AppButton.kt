package dev.axzy.stock.mobile.core.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.axzy.stock.mobile.core.theme.AppShape

/** The app's button. Every action goes through it, so the shape and the text style match everywhere. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppShape.Control,
    ) {
        Text(text)
    }
}
