package dev.axzy.stock.mobile.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark mode is not active in the web either (docs/guia/identidad-visual.md), so there is one scheme.
private val LightColors = lightColorScheme(
    primary = Brand.Primary,
    onPrimary = Color.White,
    secondary = Brand.Secondary,
    onSecondary = Color.White,
    background = Brand.Background,
    onBackground = Brand.TextPrimary,
    surface = Brand.Surface,
    onSurface = Brand.TextPrimary,
    onSurfaceVariant = Brand.TextMuted,
    error = AppColors.Danger,
    onError = Color.White,
)

@Composable
fun StockFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
