package dev.axzy.stock.mobile.feature.locations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.axzy.stock.mobile.core.data.LocationKind
import dev.axzy.stock.mobile.core.data.LocationView
import dev.axzy.stock.mobile.core.data.StockLevelView
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppRowCard
import dev.axzy.stock.mobile.core.ui.AppSearchField
import dev.axzy.stock.mobile.core.ui.DomainStatusChip
import dev.axzy.stock.mobile.core.ui.EmptyState
import dev.axzy.stock.mobile.core.ui.LoadingState

/**
 * Listado y búsqueda de ubicaciones.
 *
 * LA UBICACIÓN DE RECEPCIÓN SE LISTA PRIMERO Y SE ETIQUETA.
 * En el legacy la «ubicación» de recepción era la pseudo-ubicación `ADMIN`,
 * hardcodeada, con id 1 ficticio que podía colisionar con la ubicación real 1. Aquí
 * es una ubicación real con id real y `kind = RECEIVING`, y se muestra como tal para
 * que nadie tenga que adivinar cuál es.
 */
@Composable
fun LocationsScreen(
    repositories: AppRepositories,
    onBack: () -> Unit,
    onOpenLocation: (Long) -> Unit,
) {
    var ubicaciones by remember { mutableStateOf<List<LocationView>?>(null) }
    var consulta by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        ubicaciones = repositories.catalog.locations()
    }

    AppScaffold(title = "Ubicaciones", onBack = onBack) { modifier ->
        val lista = ubicaciones
        if (lista == null) {
            LoadingState(modifier)
            return@AppScaffold
        }

        val q = consulta.trim().uppercase()
        val visibles = if (q.isEmpty()) {
            lista
        } else {
            lista.filter {
                it.displayName.contains(q) || it.code.value.contains(q) || it.hall.contains(q)
            }
        }

        Column(modifier = modifier) {
            AppSearchField(
                value = consulta,
                onValueChange = { consulta = it },
                placeholder = "Buscar por pasillo, código o nombre",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            if (visibles.isEmpty()) {
                EmptyState("Ninguna ubicación coincide con «$consulta»")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    items(visibles, key = { it.id }) { ubicacion ->
                        LocationRow(ubicacion = ubicacion, onClick = { onOpenLocation(ubicacion.id) })
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationRow(ubicacion: LocationView, onClick: () -> Unit) {
    AppRowCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ubicacion.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = "Bodega ${ubicacion.warehouseCode} · pasillo ${ubicacion.hall} · " +
                        "${ubicacion.levels} niveles",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                )
            }
            DomainStatusChip(status = ubicacion.kind.name)
        }
        if (ubicacion.kind == LocationKind.RECEIVING) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Aquí entra la mercancía antes de ubicarse en el estante.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Info,
            )
        }
    }
}

/** Contenido de una ubicación: qué hay y cuánto. */
@Composable
fun LocationContentScreen(
    repositories: AppRepositories,
    locationId: Long,
    onBack: () -> Unit,
) {
    var contenido by remember { mutableStateOf<List<StockLevelView>?>(null) }

    LaunchedEffect(locationId) {
        contenido = repositories.catalog.locationContent(locationId)
    }

    AppScaffold(title = "Contenido", onBack = onBack) { modifier ->
        val lista = contenido
        when {
            lista == null -> LoadingState(modifier)

            lista.isEmpty() -> EmptyState(
                "Esta ubicación está vacía. Si debería tener mercancía, hay que contarla.",
                modifier,
            )

            else -> LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    val total = lista.sumOf { it.quantity }
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${lista.size} productos · $total unidades en total",
                            style = MaterialTheme.typography.titleSmall,
                            color = AppColors.TextPrimary,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
                items(lista, key = { "${it.productId}-${it.locationId}" }) { nivel ->
                    AppRowCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = nivel.productCode.value,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AppColors.TextPrimary,
                                )
                                Text(
                                    text = nivel.productName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextMuted,
                                )
                            }
                            Text(
                                text = "${nivel.quantity}",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.BrandPrimary,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}
