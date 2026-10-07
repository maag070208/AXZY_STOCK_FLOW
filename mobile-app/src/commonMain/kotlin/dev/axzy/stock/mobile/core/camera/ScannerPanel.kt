package dev.axzy.stock.mobile.core.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import dev.axzy.stock.domain.scanner.ScanInput
import dev.axzy.stock.domain.scanner.ScanState
import dev.axzy.stock.domain.scanner.ScanTarget
import dev.axzy.stock.domain.scanner.ScanWindow
import dev.axzy.stock.mobile.core.theme.AppColors
import dev.axzy.stock.mobile.core.theme.AppShape
import dev.axzy.stock.mobile.core.ui.AppButton
import dev.axzy.stock.mobile.core.ui.AppButtonVariant
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppTextField

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PANEL DE ESCANEO — un solo componente para los cuatro flujos que escanean
 *
 * Captura de factura, ubicación, movimiento de inventario y etiquetas usan el
 * MISMO panel. Si cada feature montara su cámara + su máscara + su instrucción,
 * habría cuatro sitios donde equivocarse con la ventana, que es literalmente el
 * bug M4 del legacy.
 *
 * EL ESTADO QUE RECIBE ES DEL DOMINIO ([ScanState]), no un `step` numérico. La
 * instrucción de qué escanear sale de `state.expected` y de `state.step`, y el
 * `step` se usa **solo para mostrar**: ninguna decisión depende de él. Es lo que
 * hace imposible reproducir el defecto M3, donde la rama que asignaba la ubicación
 * era inalcanzable porque el handler comparaba mal un número.
 *
 * LA ETIQUETA DE POSICIÓN (verde/ámbar) se alimenta de la MISMA comprobación que
 * hace el validador del dominio. El operador ve exactamente el criterio que se va a
 * aplicar: si el marco está verde, el escaneo entra — sin sorpresas, que es lo que
 * el legacy no podía garantizar.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * @param window la ventana compartida. Se pasa DESDE FUERA y por defecto es la del
 *   dominio, para que ninguna pantalla pueda inventarse una.
 * @param unitsEntry si no es `null`, se muestra un campo de unidades. Lo usan los
 *   flujos que necesitan la cantidad antes de la ubicación (mover mercancía).
 */
@Composable
fun ScannerPanel(
    state: ScanState,
    window: ScanWindow,
    onScan: (ScanInput) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    lastRejection: String? = null,
    positionIsValid: Boolean? = null,
    units: String? = null,
    onUnitsChange: ((String) -> Unit)? = null,
    unitsError: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {

        // ── Instrucción ─────────────────────────────────────────────────────
        AppCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = AppColors.BrandPrimaryContainer,
            borderColor = null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = AppColors.BrandPrimaryDark,
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = instruction(state),
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.BrandOnPrimaryContainer,
                    )
                    Text(
                        text = "Paso ${state.step}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.BrandPrimaryDark,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Vista de cámara + máscara ───────────────────────────────────────
        // La proporción 3:4 es fija a propósito: la ventana del dominio está
        // normalizada (0..1) **respecto al fotograma**, así que si el contenedor
        // cambiara de forma según el dispositivo, la banda dejaría de coincidir con
        // lo que ve el operador. Fijarla hace que el sistema de coordenadas del
        // dominio y el de la pantalla sean el mismo en cualquier teléfono.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(AppShape.card)
                .background(Color.Black),
        ) {
            BarCodeScannerView(
                modifier = Modifier.fillMaxSize(),
                window = window,
                isActive = isActive,
                onScan = onScan,
            )
            // La máscara va ENCIMA de la cámara y se dibuja con los datos del
            // dominio. Es la misma `window` que valida `ScanSession`.
            ScanMaskOverlay(
                window = window,
                positionIsValid = positionIsValid,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ── Rechazo ─────────────────────────────────────────────────────────
        if (lastRejection != null) {
            Spacer(Modifier.height(10.dp))
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = AppColors.Warning.copy(alpha = 0.12f),
                borderColor = AppColors.Warning,
                contentPadding = PaddingValues(12.dp),
            ) {
                Text(
                    text = lastRejection,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextPrimary,
                )
            }
        }

        // ── Unidades ────────────────────────────────────────────────────────
        // Solo aparece cuando el flujo las necesita. El teclado manual no es un
        // extra: si la etiqueta está rota o el láser no lee, el operador tiene que
        // poder teclear el código, como en el legacy.
        if (onUnitsChange != null) {
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = units ?: "",
                onValueChange = onUnitsChange,
                label = { Text("Unidades") },
                placeholder = { Text("0") },
                isError = unitsError != null,
                supportingText = unitsError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        if (state is ScanState.ProductScanned && state.units != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Producto: ${state.product.value} · ${state.units} u.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                )
            }
        }

        if (state is ScanState.ReadyToConfirm) {
            Spacer(Modifier.height(10.dp))
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = AppColors.Success.copy(alpha = 0.10f),
                borderColor = AppColors.Success,
            ) {
                Text(
                    text = "${state.product.value} · ${state.units} u. → ${state.to.value}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                )
            }
        }
    }
}

/** Instrucción para el operador según lo que toca escanear. */
private fun instruction(state: ScanState): String = when (state.expected) {
    ScanTarget.PRODUCT -> "Escanea el código del producto"
    ScanTarget.LOCATION -> "Escanea la ubicación de destino"
    ScanTarget.CONTAINER -> "Escanea el contenedor"
}

/**
 * Bloque de tecleo manual.
 *
 * POR QUÉ EXISTE: el plan exige que la captura funcione **sin red**, y la etiqueta
 * de un estante se cae, se moja o se despega. Un flujo que solo acepta cámara deja
 * al operador parado. En el legacy esto era un campo sin validación; aquí pasa por
 * la MISMA máquina de estados que el escáner, así que no hay un segundo camino con
 * reglas propias.
 */
@Composable
fun ManualCodeEntry(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Código",
) {
    var code by remember { mutableStateOf("") }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(label) },
            placeholder = { Text("Escribe el código y pulsa Añadir") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        AppButton(
            text = "Añadir",
            onClick = {
                if (code.isNotBlank()) {
                    onCode(code.trim())
                    code = ""
                }
            },
            variant = AppButtonVariant.Secondary,
        )
    }
}
