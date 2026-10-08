package dev.axzy.stock.mobile.feature.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import dev.axzy.stock.mobile.core.data.InvoiceLineView
import dev.axzy.stock.mobile.core.data.InvoiceView
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppButtonVariant
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppRowCard
import dev.axzy.stock.mobile.core.ui.DomainStatusChip
import dev.axzy.stock.mobile.core.ui.EmptyState
import dev.axzy.stock.mobile.core.ui.LoadingState

/**
 * Detalle de una factura: cabecera + líneas.
 *
 * LA `difference` SE MUESTRA, NO SE CALCULA AQUÍ PARA DECIDIR.
 * El contrato la declara **columna generada de la base** precisamente para que no
 * pueda desincronizarse (en el legacy estaba declarada en el modelo, el DTO, el
 * mapper y la respuesta, y siempre valía 0). Mientras no haya red se pinta la
 * diferencia local, pero **ninguna acción se habilita con ella**: eso lo decide el
 * servidor.
 */
@Composable
fun InvoiceDetailScreen(
    repositories: AppRepositories,
    invoiceId: Long,
    onBack: () -> Unit,
    onCapture: (Long) -> Unit,
) {
    var factura by remember { mutableStateOf<InvoiceView?>(null) }
    var lineas by remember { mutableStateOf<List<InvoiceLineView>?>(null) }

    LaunchedEffect(invoiceId) {
        factura = repositories.receiving.invoice(invoiceId)
        lineas = repositories.receiving.invoiceLines(invoiceId)
    }

    AppScaffold(
        title = factura?.invoiceNumber ?: "Factura",
        onBack = onBack,
    ) { modifier ->
        val lista = lineas
        when {
            lista == null -> LoadingState(modifier)

            lista.isEmpty() -> EmptyState("Esta factura no tiene líneas capturadas", modifier)

            else -> LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    val cabecera = factura
                    if (cabecera != null) {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Recibida ${cabecera.receiptDateIso}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                DomainStatusChip(status = cabecera.status)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${cabecera.locatedLines} de ${cabecera.totalLines} ubicadas · " +
                                    "${cabecera.pendingLines} pendientes",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextMuted,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                items(lista, key = { it.id }) { linea ->
                    InvoiceLineRow(linea = linea, onCapture = { onCapture(linea.id) })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun InvoiceLineRow(linea: InvoiceLineView, onCapture: () -> Unit) {
    AppRowCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = linea.productCode.value,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = linea.productName,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                )
            }
            DomainStatusChip(status = linea.status)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DatoEtiquetado("Esperado", "${linea.expectedQuantity} ${linea.unitMeasure}")
            DatoEtiquetado("Físico", linea.physicalQuantity?.let { "$it" } ?: "—")
            DatoEtiquetado(
                label = "Diferencia",
                value = linea.difference?.let { if (it == 0.0) "0" else "$it" } ?: "—",
                color = when {
                    linea.difference == null -> AppColors.TextFaint
                    linea.difference == 0.0 -> AppColors.Success
                    else -> AppColors.Danger
                },
            )
        }

        val ubicada = linea.locationDisplayName
        if (ubicada != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Ubicada en $ubicada",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Success,
            )
        }

        Spacer(Modifier.height(10.dp))
        AppButton(
            text = if (linea.physicalQuantity == null) "Capturar" else "Recapturar",
            onClick = onCapture,
            variant = AppButtonVariant.Secondary,
            leadingContent = {
                androidx.compose.material3.Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            },
        )
    }
}

@Composable
private fun DatoEtiquetado(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = AppColors.TextPrimary,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextFaint)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
