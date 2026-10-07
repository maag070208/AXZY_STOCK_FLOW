package dev.axzy.stock.mobile.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoveDown
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.AppScaffold
import dev.axzy.stock.mobile.core.data.AppRepositories
import dev.axzy.stock.mobile.core.data.HomeCategory
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.theme.AppShape
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppModal
import dev.axzy.stock.mobile.core.ui.AppModalTone
import dev.axzy.stock.mobile.core.ui.LoadingState

/**
 * Menú de inicio: grid de categorías con sus contadores.
 *
 * PATRÓN: MVVM simple. Es una lista con un contador; no hay secuencia que romper.
 *
 * POR QUÉ LLEVA CONTADORES Y NO SOLO ICONOS: el operador necesita saber **dónde hay
 * trabajo** antes de entrar. Un menú sin números obliga a abrir las cinco secciones
 * para descubrir que solo una tenía pendientes, y en un almacén eso son cinco viajes
 * de pantalla con el teléfono en la mano.
 */
@Composable
fun HomeScreen(
    repositories: AppRepositories,
    user: String?,
    onOpen: (HomeCategory) -> Unit,
    onLogout: () -> Unit,
) {
    var categorias by remember { mutableStateOf<List<HomeCategory>?>(null) }
    var confirmarSalida by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        categorias = repositories.home.categories()
    }

    AppScaffold(
        title = if (user.isNullOrBlank()) "Almacén Integral" else "Hola, $user",
        onBack = null,
        actions = {
            IconButton(onClick = { confirmarSalida = true }) {
                Icon(Icons.Filled.Logout, contentDescription = "Cerrar sesión")
            }
        },
    ) { modifier ->
        val lista = categorias
        if (lista == null) {
            LoadingState(modifier)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = modifier,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(lista, key = { it.id }) { categoria ->
                    HomeTile(categoria = categoria, onClick = { onOpen(categoria) })
                }
            }
        }
    }

    if (confirmarSalida) {
        AppModal(
            title = "Cerrar sesión",
            onDismiss = { confirmarSalida = false },
            tone = AppModalTone.Danger,
            icon = Icons.Filled.Logout,
            confirmLabel = "Cerrar sesión",
            onConfirm = {
                confirmarSalida = false
                onLogout()
            },
        ) {
            // Se avisa de lo que NO se pierde. Un operador que teme perder su captura
            // del día no cierra sesión aunque deba, y acaba compartiendo el teléfono.
            Text(
                text = "Se descarta la sesión local. La cola de movimientos pendientes " +
                    "de sincronizar NO se borra.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextMuted,
            )
        }
    }
}

@Composable
private fun HomeTile(categoria: HomeCategory, onClick: () -> Unit) {
    val icono: ImageVector = when (categoria.id) {
        "invoices" -> Icons.Filled.Sell
        "orders" -> Icons.Filled.LocalShipping
        "locations" -> Icons.Filled.Category
        "inventory" -> Icons.Filled.MoveDown
        "labels" -> Icons.Filled.Print
        else -> Icons.Filled.Inventory2
    }
    val acento: Color = when (categoria.id) {
        "invoices" -> AppColors.BrandPrimary
        "orders" -> AppColors.Info
        "locations" -> AppColors.Purple
        "inventory" -> AppColors.Success
        "labels" -> AppColors.Warning
        else -> AppColors.Slate
    }

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(acento.copy(alpha = 0.12f), AppShape.icon),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icono, contentDescription = null, tint = acento, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = categoria.label,
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.TextPrimary,
        )
        Text(
            text = categoria.description,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextMuted,
        )
        if (categoria.count != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (categoria.count > 0) "${categoria.count} pendientes" else "Sin pendientes",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (categoria.count > 0) AppColors.Warning else AppColors.Success,
                )
            }
        }
    }
}
