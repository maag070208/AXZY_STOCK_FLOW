package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * How far a piece of work has come, from 0 to 1, as one continuous bar. [onDark] paints it for a dark background
 * (the hero).
 */
@Composable
fun ITProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.height(8.dp),
        color = if (onDark) colors.onPrimary else colors.primary,
        trackColor = if (onDark) colors.onPrimary.copy(alpha = 0.25f) else colors.outlineVariant,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
