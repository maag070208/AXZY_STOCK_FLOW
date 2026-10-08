package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.theme.AppShape

/**
 * Énfasis del botón. Tres valores, ni uno más: en una pantalla operativa
 * **una sola** acción primaria. Si hay dos botones rellenos compitiendo, el
 * operador pulsa el equivocado, y aquí el equivocado confirma un movimiento de
 * inventario.
 */
enum class AppButtonVariant { Primary, Secondary, Text }

/**
 * Botón estándar de la app (fuente única de apariencia).
 *
 * POR QUÉ UN SOLO COMPONENTE CON VARIANTE Y NO TRES BOTONES: porque las tres
 * variantes comparten `enabled`, `loading`, radio y padding, y con tres
 * componentes separados los tres se desincronizan en la primera prisa. Aquí la
 * forma y el padding viven en un solo sitio.
 *
 * [loading] deshabilita el botón además de girar el indicador: sin eso, el
 * usuario puede pulsar «Confirmar movimiento» dos veces mientras la primera
 * petición viaja, y eso es exactamente un movimiento duplicado.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingContent: @Composable (RowScope.() -> Unit)? = null,
) {
    val contenido: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = if (variant == AppButtonVariant.Primary) AppColors.OnPrimary else AppColors.BrandPrimary,
            )
        } else {
            leadingContent?.invoke(this)
            Text(text)
        }
    }

    // Mientras carga, el botón está deshabilitado. Es la salvaguarda contra la
    // doble pulsación, y por eso no se deja a criterio de quien lo usa.
    val habilitado = enabled && !loading

    val minSize = Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 48.dp)
    val padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)

    when (variant) {
        AppButtonVariant.Primary -> Button(
            onClick = onClick,
            modifier = modifier.then(minSize),
            enabled = habilitado,
            shape = AppShape.field,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.BrandPrimary,
                contentColor = AppColors.OnPrimary,
            ),
            content = contenido,
        )

        AppButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.then(minSize),
            enabled = habilitado,
            shape = AppShape.field,
            contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AppColors.BrandPrimary,
            ),
            content = contenido,
        )

        AppButtonVariant.Text -> TextButton(
            onClick = onClick,
            modifier = modifier.then(minSize),
            enabled = habilitado,
            shape = AppShape.field,
            contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.TextMuted),
            content = contenido,
        )
    }
}

/**
 * Acción destructiva (cancelar factura, eliminar plantilla). Se separa de
 * [AppButton] porque el color no es decorativo: `AppColors.Danger` es el aviso
 * de que la acción no se deshace.
 */
@Composable
fun AppDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = 56.dp, minHeight = 48.dp),
        enabled = enabled && !loading,
        shape = AppShape.field,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.Danger,
            contentColor = Color.White,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
