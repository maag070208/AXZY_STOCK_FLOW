package dev.axzy.stock.mobile.feature.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.mobile.AppScaffold
import dev.axzy.stock.mobile.core.data.AppRepositories
import dev.axzy.stock.mobile.core.data.InvoiceLineView
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppButtonVariant
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppModal
import dev.axzy.stock.mobile.core.ui.AppModalTone
import dev.axzy.stock.mobile.core.ui.AppTextField
import dev.axzy.stock.mobile.core.ui.DomainStatusChip
import dev.axzy.stock.mobile.core.ui.LoadingState
import kotlinx.coroutines.launch

/**
 * Captura física de una línea de factura.
 *
 * PATRÓN: MVVM con la validación delegada al DOMINIO.
 *
 * POR QUÉ NO HAY AQUÍ UNA MÁQUINA DE ESTADOS PROPIA: capturar una línea es
 * *teclear una cantidad y confirmarla*, sin secuencia de pasos. Lo que sí es una
 * regla es **qué cantidad es válida**, y eso ya está en `Quantity` del dominio
 * (`Quantity.parse` rechaza texto no numérico en lugar de asumir 0, que es lo que
 * el legacy hacía con `parseInt` y de ahí venían líneas capturadas en cero).
 *
 * LA CLAVE DE IDEMPOTENCIA SE GENERA AQUÍ Y NO SE REGENERA EN CADA REINTENTO:
 * es el punto del contrato. Si se generara por intento, un reintento sin señal
 * crearía un movimiento nuevo en lugar de devolver el mismo resultado.
 */
@Composable
fun InvoiceCaptureScreen(
    repositories: AppRepositories,
    invoiceId: Long,
    lineId: Long,
    onBack: () -> Unit,
) {
    var linea by remember { mutableStateOf<InvoiceLineView?>(null) }
    var texto by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var guardando by remember { mutableStateOf(false) }
    var confirmacion by remember { mutableStateOf<String?>(null) }

    // Una sola clave por apertura de pantalla: reintentar el mismo envío reutiliza
    // la clave, que es exactamente lo que exige el contrato de idempotencia.
    val idempotencyKey = remember(invoiceId, lineId) {
        "cap-$invoiceId-$lineId-${(0..999999).random()}"
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(invoiceId, lineId) {
        val actual = repositories.receiving.invoiceLines(invoiceId).firstOrNull { it.id == lineId }
        linea = actual
        texto = actual?.physicalQuantity?.toString() ?: ""
    }

    val actual = linea

    AppScaffold(title = "Capturar línea", onBack = onBack) { modifier ->
        if (actual == null) {
            LoadingState(modifier)
            return@AppScaffold
        }

        Column(
            modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // ── Qué se está capturando ──────────────────────────────────────
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = actual.productCode.value,
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.TextPrimary,
                        )
                        Text(
                            text = actual.productName,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextMuted,
                        )
                    }
                    DomainStatusChip(status = actual.status)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Dato("Esperado", "${actual.expectedQuantity} ${actual.unitMeasure}")
                    Dato("Ubicada", actual.locatedQuantity.toString())
                    Dato(
                        label = "Diferencia actual",
                        value = actual.difference?.toString() ?: "—",
                        color = if (actual.difference == null || actual.difference == 0.0) {
                            AppColors.TextPrimary
                        } else {
                            AppColors.Danger
                        },
                    )
                }
                if (actual.unitsPerBox > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Unidades por caja: ${actual.unitsPerBox}" +
                            (actual.container?.let { " · Contenedor $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextFaint,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Cantidad física ─────────────────────────────────────────────
            AppTextField(
                value = texto,
                onValueChange = {
                    texto = it
                    error = null
                },
                label = { Text("Cantidad física contada") },
                placeholder = { Text("0") },
                supportingText = {
                    Text("Se admiten hasta 3 decimales. Se compara contra lo esperado.")
                },
                isError = error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))

            AppButton(
                text = "Guardar captura",
                onClick = {
                    // La validación la hace el DOMINIO: `Quantity.parse` rechaza el
                    // texto no numérico y los decimales de más. Volver a validar aquí
                    // con un `toDoubleOrNull` sería una segunda regla que puede
                    // discrepar de la del servidor.
                    val cantidad = Quantity.parse(texto).getOrElse { fallo ->
                        error = fallo.message ?: "Cantidad inválida"
                        return@AppButton
                    }
                    if (!cantidad.isPositive) {
                        error = "La cantidad debe ser mayor que cero"
                        return@AppButton
                    }

                    scope.launch {
                        guardando = true
                        val resultado = repositories.receiving.captureLine(
                            invoiceId = invoiceId,
                            lineId = lineId,
                            // `Quantity` representa milésimas (escala 3): se pasa al
                            // valor decimal que espera el contrato, no el Long interno.
                            physicalQuantity = cantidad.value / 1_000.0,
                            idempotencyKey = idempotencyKey,
                        )
                        guardando = false
                        resultado.fold(
                            onSuccess = { actualizada ->
                                linea = actualizada
                                confirmacion = if (actualizada.status == "DISCREPANCY") {
                                    "Capturada con discrepancia: ${actualizada.difference}. " +
                                        "Una línea en discrepancia NO se puede ubicar hasta que " +
                                        "la apruebe otra persona."
                                } else {
                                    "Capturada y coincidente. Ya se puede ubicar."
                                }
                            },
                            onFailure = { error = it.message ?: "No se pudo guardar la captura" },
                        )
                    }
                },
                loading = guardando,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmacion != null) {
        AppModal(
            title = "Captura guardada",
            onDismiss = { confirmacion = null },
            icon = Icons.Filled.CheckCircle,
            tone = if (confirmacion!!.contains("discrepancia")) AppModalTone.Warning else AppModalTone.Success,
            confirmLabel = "Entendido",
            onConfirm = { confirmacion = null },
        ) {
            Text(
                text = confirmacion!!,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextMuted,
            )
        }
    }
}

@Composable
private fun Dato(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = AppColors.TextPrimary,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextFaint)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
