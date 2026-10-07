package dev.axzy.stock.mobile.feature.inventory.move

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.axzy.stock.domain.scanner.ScanWindow
import dev.axzy.stock.mobile.AppScaffold
import dev.axzy.stock.mobile.core.camera.ManualCodeEntry
import dev.axzy.stock.mobile.core.camera.ScannerPanel
import dev.axzy.stock.mobile.core.data.AppRepositories
import dev.axzy.stock.mobile.core.data.ProductView
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppButtonVariant
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppModal
import dev.axzy.stock.mobile.core.ui.AppModalTone
import dev.axzy.stock.mobile.core.ui.LoadingState
import dev.axzy.stock.mobile.core.ui.SectionLabel

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MOVER MERCANCÍA — la pantalla del escáner de dos pasos
 *
 * PATRÓN: MVI (PLAN §3.4). La pantalla NO tiene lógica de flujo:
 *   1. recibe el estado ([MoveStockUiState]),
 *   2. pinta,
 *   3. emite [MoveStockIntent].
 *
 * Toda la decisión de «qué toca escanear ahora» y «este escaneo vale» está en
 * `reduceMoveStock` (puro) y en `ScanState.onScan` del **dominio**. La pantalla no
 * puede desincronizarse de esas reglas porque no las conoce.
 *
 * LA VENTANA ES UNA SOLA: `ScanWindow.DEFAULT` del dominio. Se pasa al panel, que
 * la usa TANTO para dibujar la máscara COMO para validar (vía el reducer). El legacy
 * tenía la máscara de 300×100 y la validación de 250×50, y por eso descartaba
 * escaneos que el operador veía perfectamente centrados.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Composable
fun MoveStockScreen(
    repositories: AppRepositories,
    mode: MoveMode,
    onBack: () -> Unit,
    window: ScanWindow = ScanWindow.DEFAULT,
) {
    var productos by remember { mutableStateOf<List<ProductView>?>(null) }
    var viewModel by remember { mutableStateOf<MoveStockViewModel?>(null) }
    var mensaje by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mode) {
        val cargados = repositories.catalog.searchProducts("")
        productos = cargados
        viewModel = MoveStockViewModel(
            catalog = repositories.catalog,
            products = cargados,
            mode = mode,
            window = window,
        )
    }

    val vm = viewModel
    if (vm == null) {
        AppScaffold(title = mode.title, onBack = onBack) { modifier -> LoadingState(modifier) }
        return
    }

    val estado by vm.state.collectAsStateWithLifecycle()

    // Los avisos de un solo uso llegan por un `Channel`, no por el estado: si
    // vivieran en el estado, al rotar la pantalla reaparecería el «confirmado» de
    // hace un minuto y el operador creería que confirmó dos veces.
    LaunchedEffect(vm) {
        vm.events.collect { mensaje = it }
    }

    AppScaffold(title = mode.title, onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ScannerPanel(
                state = estado.scan,
                window = window,
                onScan = { vm.onIntent(MoveStockIntent.Scanned(it)) },
                lastRejection = estado.rejection,
                positionIsValid = estado.positionValid,
                units = estado.unitsText,
                onUnitsChange = { vm.onIntent(MoveStockIntent.UnitsTyped(it)) },
                unitsError = estado.unitsError,
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Si la etiqueta no se lee")
            ManualCodeEntry(
                onCode = { codigo ->
                    // El tecleo manual entra por la MISMA máquina de estados que la
                    // cámara. Se le da el centro del fotograma como posición: un
                    // código tecleado no tiene posición, y hacerlo pasar por la
                    // validación de ventana rechazaría algo que el operador escribió
                    // a propósito.
                    vm.onIntent(
                        MoveStockIntent.Scanned(
                            dev.axzy.stock.domain.scanner.ScanInput(
                                raw = codigo,
                                bounds = centroDeLaVentana(window),
                            ),
                        ),
                    )
                },
                label = "Código",
            )

            Spacer(Modifier.height(20.dp))

            AppButton(
                text = "Confirmar movimiento",
                onClick = { vm.onIntent(MoveStockIntent.Confirmed) },
                enabled = estado.canConfirm,
                loading = estado.submitting,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!estado.canConfirm && !estado.submitting) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Escanea el producto, escribe las unidades y escanea la ubicación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextFaint,
                )
            }

            if (estado.confirmationMessage != null) {
                Spacer(Modifier.height(16.dp))
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = AppColors.Success.copy(alpha = 0.10f),
                    borderColor = AppColors.Success,
                ) {
                    Text(
                        text = estado.confirmationMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextPrimary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            AppButton(
                text = "Empezar de nuevo",
                onClick = { vm.onIntent(MoveStockIntent.Reset) },
                variant = AppButtonVariant.Text,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.padding(bottom = 24.dp).height(1.dp))
        }
    }

    if (mensaje != null) {
        AppModal(
            title = "Movimiento preparado",
            onDismiss = { mensaje = null },
            icon = Icons.Filled.CheckCircle,
            tone = AppModalTone.Success,
            confirmLabel = "Entendido",
            onConfirm = { mensaje = null },
        ) {
            Text(
                text = mensaje!!,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextMuted,
            )
        }
    }
}

/**
 * Centro de la ventana de validación, como posición de un código tecleado.
 *
 * Se usa la ventana del dominio en lugar de una constante: si mañana la banda se
 * mueve de sitio, el tecleo manual sigue pasando por el mismo punto.
 */
private fun centroDeLaVentana(window: ScanWindow) = dev.axzy.stock.domain.scanner.ScanRect(
    left = (window.window.centerX - 0.001).coerceIn(0.0, 0.999),
    top = (window.window.centerY - 0.001).coerceIn(0.0, 0.999),
    width = 0.002,
    height = 0.002,
)
