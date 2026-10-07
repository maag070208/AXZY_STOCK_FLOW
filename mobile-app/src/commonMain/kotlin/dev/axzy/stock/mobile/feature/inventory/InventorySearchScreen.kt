package dev.axzy.stock.mobile.feature.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoveDown
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.AppScaffold
import dev.axzy.stock.mobile.core.data.AppRepositories
import dev.axzy.stock.mobile.core.data.ProductView
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.ui.AppActionCard
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppRowCard
import dev.axzy.stock.mobile.core.ui.AppSearchField
import dev.axzy.stock.mobile.core.ui.EmptyState
import dev.axzy.stock.mobile.core.ui.LoadingState
import dev.axzy.stock.mobile.core.ui.SectionLabel
import dev.axzy.stock.mobile.feature.inventory.move.MoveMode

/**
 * Inventario: elegir la operación y buscar el producto.
 *
 * LAS DOS OPERACIONES LLEVAN AL MISMO FLUJO con distinto [MoveMode].
 * Es la decisión de §3.4: el legacy tenía `MoveProductLocation` y
 * `MoveProductAdmin` como dos pantallas que hacían casi lo mismo y se
 * desincronizaron (una permitía mover a ADMIN y la otra no). Aquí la diferencia es
 * un parámetro, no una pantalla duplicada.
 */
@Composable
fun InventorySearchScreen(
    repositories: AppRepositories,
    onBack: () -> Unit,
    onMove: (MoveMode) -> Unit,
) {
    var productos by remember { mutableStateOf<List<ProductView>?>(null) }
    var consulta by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        productos = repositories.catalog.searchProducts("")
    }

    AppScaffold(title = "Inventario", onBack = onBack) { modifier ->
        Column(modifier = modifier) {
            AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                SectionLabel("Operaciones")
                Row(modifier = Modifier.fillMaxWidth()) {
                    AppActionCard(
                        label = "Ubicar en estante",
                        icon = Icons.Filled.MoveDown,
                        onClick = { onMove(MoveMode.LOCATE) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.padding(horizontal = 6.dp).height(1.dp))
                    AppActionCard(
                        label = "Mover entre estantes",
                        icon = Icons.Filled.SwapHoriz,
                        onClick = { onMove(MoveMode.BETWEEN_SHELVES) },
                        modifier = Modifier.weight(1f),
                        accent = AppColors.Info,
                    )
                }
            }

            AppSearchField(
                value = consulta,
                onValueChange = { consulta = it },
                placeholder = "Buscar producto por código o nombre",
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(12.dp))

            val lista = productos
            when {
                lista == null -> LoadingState()

                else -> {
                    val q = consulta.trim().uppercase()
                    val visibles = if (q.isEmpty()) {
                        lista
                    } else {
                        lista.filter { it.code.value.contains(q) || it.name.uppercase().contains(q) }
                    }
                    if (visibles.isEmpty()) {
                        EmptyState("Ningún producto coincide con «$consulta»")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        ) {
                            items(visibles, key = { it.id }) { producto ->
                                ProductRow(producto)
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(producto: ProductView) {
    AppRowCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = producto.code.value,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = producto.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${producto.totalStock}",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.BrandPrimary,
                )
                Text(
                    text = producto.unitMeasure,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextFaint,
                )
            }
        }
    }
}
