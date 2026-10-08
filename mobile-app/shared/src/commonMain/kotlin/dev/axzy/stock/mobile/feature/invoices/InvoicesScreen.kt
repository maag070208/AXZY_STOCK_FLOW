package dev.axzy.stock.mobile.feature.invoices

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
import dev.axzy.stock.mobile.core.data.InvoiceView
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.ui.AppRowCard
import dev.axzy.stock.mobile.core.ui.DomainStatusChip
import dev.axzy.stock.mobile.core.ui.EmptyState
import dev.axzy.stock.mobile.core.ui.LoadingState

/**
 * Listado de facturas de recepción.
 *
 * PATRÓN: MVVM simple (lista + navegación). La máquina de estados aparece al
 * capturar, no aquí.
 *
 * LO QUE MUESTRA Y POR QUÉ: `pendingLines` es el dato que decide a qué factura
 * entrar. El legacy no lo tenía y había que abrir cada factura para contar las
 * líneas a mano.
 */
@Composable
fun InvoicesScreen(
    repositories: AppRepositories,
    onBack: () -> Unit,
    onOpenInvoice: (Long) -> Unit,
) {
    var facturas by remember { mutableStateOf<List<InvoiceView>?>(null) }
    var filtro by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        facturas = repositories.receiving.invoices()
    }

    AppScaffold(title = "Recepción", onBack = onBack) { modifier ->
        val lista = facturas
        when {
            lista == null -> LoadingState(modifier)

            lista.isEmpty() -> EmptyState("No hay facturas registradas", modifier)

            else -> {
                val visibles = lista.filter { filtro == null || it.status == filtro }
                Column(modifier = modifier) {
                    // Filtros por estado con el chip estándar: ninguna pantalla
                    // construye sus propias «pills» clicables.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        dev.axzy.stock.mobile.core.ui.AppFilterChip(
                            label = "Todas",
                            selected = filtro == null,
                            onClick = { filtro = null },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        dev.axzy.stock.mobile.core.ui.AppFilterChip(
                            label = "En proceso",
                            selected = filtro == "IN_PROGRESS",
                            onClick = { filtro = "IN_PROGRESS" },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        dev.axzy.stock.mobile.core.ui.AppFilterChip(
                            label = "Por ubicar",
                            selected = filtro == "PENDING_TO_LOCATE",
                            onClick = { filtro = "PENDING_TO_LOCATE" },
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    ) {
                        items(visibles, key = { it.id }) { factura ->
                            InvoiceRow(factura = factura, onClick = { onOpenInvoice(factura.id) })
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvoiceRow(factura: InvoiceView, onClick: () -> Unit) {
    AppRowCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = factura.invoiceNumber,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = "Recibida ${factura.receiptDateIso}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${factura.locatedLines} de ${factura.totalLines} líneas ubicadas" +
                        if (factura.pendingLines > 0) " · ${factura.pendingLines} pendientes" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (factura.pendingLines > 0) AppColors.Warning else AppColors.Success,
                )
            }
            DomainStatusChip(status = factura.status)
        }
    }
}
