package dev.axzy.stock.mobile.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    // Without these, Material falls back to its own grey, which has a lilac cast next to the brand's navy.
    surfaceVariant = Brand.Border,
    outline = Brand.BorderStrong,
    outlineVariant = Brand.Border,
    error = ITColors.Danger,
    onError = Color.White,
)

// Material's own components take their corners from here, so they match ITShape without extra code.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = ITShape.Control,
    medium = ITShape.Row,
    large = ITShape.Card,
    extraLarge = ITShape.Card,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.4.sp),
)

@Composable
fun StockFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
