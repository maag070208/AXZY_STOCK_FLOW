package dev.axzy.stock.mobile.feature.orders

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
import dev.axzy.stock.mobile.core.data.OrderView
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppRowCard
import dev.axzy.stock.mobile.core.ui.DomainStatusChip
import dev.axzy.stock.mobile.core.ui.EmptyState
import dev.axzy.stock.mobile.core.ui.LoadingState
import dev.axzy.stock.mobile.core.ui.SectionLabel

/**
 * Listado de órdenes de surtido.
 *
 * `exportStatus` se muestra cuando existe porque **es el estado del ERP**, no un
 * detalle técnico: una orden confirmada que no llegó al ERP es una venta que el
 * cliente no verá, y el legacy no lo mostraba en ninguna pantalla.
 */
@Composable
fun OrdersScreen(
    repositories: AppRepositories,
    onBack: () -> Unit,
    onOpenOrder: (Long) -> Unit,
) {
    var ordenes by remember { mutableStateOf<List<OrderView>?>(null) }

    LaunchedEffect(Unit) {
        ordenes = repositories.fulfillment.orders()
    }

    AppScaffold(title = "Surtido", onBack = onBack) { modifier ->
        val lista = ordenes
        when {
            lista == null -> LoadingState(modifier)
            lista.isEmpty() -> EmptyState("No hay órdenes de surtido", modifier)
            else -> LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(16.dp),
            ) {
                items(lista, key = { it.id }) { orden ->
                    OrderRow(orden = orden, onClick = { onOpenOrder(orden.id) })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun OrderRow(orden: OrderView, onClick: () -> Unit) {
    AppRowCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = orden.orderNumber,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = "Empaque ${orden.packagingDateIso}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                )
            }
            DomainStatusChip(status = orden.status)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${orden.packagedLines} de ${orden.totalLines} líneas empacadas",
            style = MaterialTheme.typography.bodySmall,
            color = if (orden.packagedLines < orden.totalLines) AppColors.Warning else AppColors.Success,
        )
        if (orden.exportStatus != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ERP: ",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextFaint,
                )
                DomainStatusChip(status = orden.exportStatus!!)
            }
        }
    }
}

/** Detalle de una orden. Lista lo que falta y el estado de la exportación al ERP. */
@Composable
fun OrderDetailScreen(
    repositories: AppRepositories,
    orderId: Long,
    onBack: () -> Unit,
) {
    var orden by remember { mutableStateOf<OrderView?>(null) }

    LaunchedEffect(orderId) {
        orden = repositories.fulfillment.order(orderId)
    }

    AppScaffold(title = orden?.orderNumber ?: "Orden", onBack = onBack) { modifier ->
        val actual = orden
        if (actual == null) {
            LoadingState(modifier)
            return@AppScaffold
        }

        Column(modifier = modifier.padding(16.dp)) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Empaque ${actual.packagingDateIso}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextPrimary,
                        )
                        if (actual.customerCode != null) {
                            Text(
                                text = "Cliente ${actual.customerCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextMuted,
                            )
                        }
                    }
                    DomainStatusChip(status = actual.status)
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Avance")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Columna("Líneas", actual.totalLines.toString())
                    Columna("Empacadas", actual.packagedLines.toString())
                    Columna("Pendientes", (actual.totalLines - actual.packagedLines).toString())
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Exportación al ERP")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // Se explica el estado en palabras, no con el código del enum:
                        // «converted = true» no le dice nada a quien surte.
                        text = if (actual.converted) {
                            "La orden se confirmó y se envió al ERP."
                        } else {
                            "La orden aún no se ha confirmado; no se ha enviado al ERP."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (actual.exportStatus != null) DomainStatusChip(status = actual.exportStatus!!)
                }
            }
        }
    }
}

@Composable
private fun Columna(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextFaint)
        Text(value, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
    }
}
