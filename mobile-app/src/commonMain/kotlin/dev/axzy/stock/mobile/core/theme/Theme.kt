package dev.axzy.stock.mobile.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tema de la app.
 *
 * SOLO HAY TEMA CLARO, y es una decisión, no un olvido: el personal de almacén
 * trabaja en pasillos con luz cambiante y bajo el sol en el patio de maniobras.
 * Un tema oscuro en esas condiciones reduce el contraste del texto, que es
 * justo lo contrario de lo que hace falta. Cuando haya tema oscuro será porque
 * alguien lo ha probado en el pasillo, no porque el sistema lo pida.
 */
private val LightColors = lightColorScheme(
    primary = AppColors.BrandPrimary,
    onPrimary = AppColors.OnPrimary,
    primaryContainer = AppColors.BrandPrimaryContainer,
    onPrimaryContainer = AppColors.BrandOnPrimaryContainer,
    secondary = AppColors.BrandAccent,
    onSecondary = AppColors.BrandOnAccent,
    secondaryContainer = AppColors.BrandSecondaryContainer,
    onSecondaryContainer = AppColors.BrandOnSecondaryContainer,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceVariant,
    onSurfaceVariant = AppColors.TextMuted,
    outline = AppColors.Outline,
    error = AppColors.Danger,
    onError = AppColors.OnPrimary,
)

private val AppShapes = Shapes(
    extraSmall = AppShape.pill,
    small = AppShape.field,
    medium = AppShape.row,
    large = AppShape.card,
    extraLarge = AppShape.card,
)

/**
 * Tipografía del design system.
 *
 * Los pesos y tamaños están calcados del ejemplo real. Lo que importa aquí es
 * que las etiquetas (`labelSmall`) sean legibles a un brazo de distancia: son las
 * que van dentro de los chips de estado, y son el dato que el operador lee de
 * reojo para decidir si pulsa.
 */
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.2.sp),
)

@Composable
fun AlmacenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
