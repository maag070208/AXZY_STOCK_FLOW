package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.theme.AppShape

/**
 * Chip de estado. La forma (`AppShape.pill`) y el color vienen de los tokens:
 * un chip no es un adorno, es el dato que el operador lee de reojo para decidir
 * si una línea está aprobada o en discrepancia.
 *
 * El color se pasa ya resuelto desde [AppColors.statusColor] para que el mismo
 * estado se vea igual en todas las pantallas.
 */
@Composable
fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), AppShape.pill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Chip de estado del dominio: recibe el nombre del estado y resuelve el color.
 * Preferir este a [StatusChip] cuando el texto ES un estado del dominio.
 */
@Composable
fun DomainStatusChip(status: String, modifier: Modifier = Modifier) {
    StatusChip(label = status, color = AppColors.statusColor(status), modifier = modifier)
}

/** Chip de filtro seleccionable (pills clicables). */
@Composable
fun AppFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectedColor: Color = AppColors.BrandPrimary,
) {
    val background = when {
        selected && enabled -> selectedColor
        selected -> selectedColor.copy(alpha = 0.45f)
        else -> AppColors.SurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            selected -> AppColors.OnPrimary
            enabled -> AppColors.TextMuted
            else -> AppColors.TextFaint
        },
        modifier = modifier
            // `clip` ANTES de `background`/`clickable`: si el orden se invierte,
            // el ripple se dibuja cuadrado sobre una cápsula y se ve el defecto.
            .clip(AppShape.pill)
            .background(background, AppShape.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Tarjeta estándar. Recorta fondo, borde y efecto de pulsado a la MISMA forma:
 * sin eso, al presionar aparece un rectángulo detrás de una tarjeta redondeada.
 *
 * Es el único sitio donde se construye una tarjeta. Una pantalla no debe juntar
 * `background + border + clickable` por su cuenta.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = AppShape.card,
    containerColor: Color = AppColors.Surface,
    borderColor: Color? = AppColors.Outline.copy(alpha = 0.6f),
    elevation: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .shadow(elevation = elevation, shape = shape, clip = false)
            .clip(shape)
            .background(containerColor, shape)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Tarjeta contenedora con borde, sin acción: agrupa contenido relacionado. */
@Composable
fun AppSurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(
        modifier = modifier,
        borderColor = AppColors.Outline.copy(alpha = 0.65f),
        content = content,
    )
}

/** Tile de acceso rápido: icono en contenedor de acento + etiqueta. */
@Composable
fun AppActionCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AppColors.BrandPrimary,
) {
    AppCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(accent.copy(alpha = 0.12f), AppShape.icon),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
    }
}

/**
 * Tarjeta de fila: fila pulsable dentro de una lista. Usa [AppShape.row] y su
 * contenido lleva 8dp de padding respecto al borde, que es lo que hace que el
 * ripple no toque el texto.
 */
@Composable
fun AppRowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = AppShape.row,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        borderColor = AppColors.Outline.copy(alpha = 0.5f),
        content = content,
    )
}

/** Etiqueta de sección en mayúsculas. Un solo estilo para todas. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        color = AppColors.TextMuted,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

/** Estado de carga a pantalla completa. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AppColors.BrandPrimary)
    }
}

/** Estado de error con reintento opcional. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = AppColors.Danger,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextMuted,
                modifier = Modifier.padding(top = 10.dp, bottom = 14.dp),
            )
            if (onRetry != null) {
                AppButton(text = "Reintentar", onClick = onRetry, variant = AppButtonVariant.Secondary)
            }
        }
    }
}

/** Estado vacío: «no hay nada», que no es lo mismo que «falló». */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Inbox,
                contentDescription = null,
                tint = AppColors.TextFaint,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextFaint,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Fila vacía dentro de una tabla: no ocupa la pantalla entera. */
@Composable
fun EmptyRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, color = AppColors.TextFaint, style = MaterialTheme.typography.bodySmall)
    }
}

/** Tono del modal: cambia el color del acento y del botón de confirmación. */
enum class AppModalTone { Neutral, Danger, Success, Warning }

/**
 * Modal estándar. Es el único diálogo de la app: los flujos destructivos (anular
 * factura, reimprimir etiqueta) exigen motivo, y sin un componente único cada
 * pantalla acabaría con su propio `AlertDialog` y sus propias reglas.
 */
@Composable
fun AppModal(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: AppModalTone = AppModalTone.Neutral,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    saving: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = when (tone) {
        AppModalTone.Neutral -> AppColors.BrandPrimary
        AppModalTone.Danger -> AppColors.Danger
        AppModalTone.Success -> AppColors.Success
        AppModalTone.Warning -> AppColors.Warning
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.fillMaxWidth().widthIn(max = 440.dp),
            shape = AppShape.card,
            color = AppColors.Surface,
            border = BorderStroke(1.dp, AppColors.Outline),
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(accent.copy(alpha = 0.12f), AppShape.icon),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = null, tint = accent)
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
                }
                Spacer(Modifier.height(18.dp))
                content()
                if (onConfirm != null && confirmLabel != null) {
                    Spacer(Modifier.height(22.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        AppButton(
                            text = "Cancelar",
                            onClick = onDismiss,
                            variant = AppButtonVariant.Text,
                        )
                        Spacer(Modifier.width(8.dp))
                        AppButton(
                            text = confirmLabel,
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            loading = saving,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Desplegable estándar. Existe para que ninguna pantalla reimplemente el
 * `DropdownMenu` con su propio `Surface` clicable.
 */
@Composable
fun AppDropdownField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = options.firstOrNull { it.first == value }?.second ?: value
    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = AppShape.field,
            color = AppColors.Surface,
            border = BorderStroke(1.dp, AppColors.Outline),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextFaint)
                Text(displayLabel, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (optValue, optLabel) ->
                DropdownMenuItem(
                    text = { Text(optLabel) },
                    onClick = {
                        onSelect(optValue)
                        expanded = false
                    },
                )
            }
        }
    }
}
