package dev.axzy.stock.mobile.core.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.axzy.stock.domain.scanner.ScanInput
import dev.axzy.stock.domain.scanner.ScanWindow

/**
 * `actual` de JVM.
 *
 * POR QUÉ EXISTE ESTE TARGET Y ESTE STUB, SI LA APP ES MÓVIL
 *
 * El target JVM no es para «escritorio»: es donde corren los tests del reducer y de
 * los ViewModels, que es el criterio de aceptación de la Fase 6 («los tests con
 * máquina de estados pasan en JVM sin arrancar emulador»). Kotlin exige un `actual`
 * para cada target, así que este archivo es el precio de poder probar en
 * milisegundos en lugar de en un emulador.
 *
 * NO SIMULA UNA CÁMARA A PROPÓSITO. Un falso escáner en JVM haría que un test
 * «pasara» leyendo códigos que nunca se escanearon; los tests del escáner deben
 * alimentar la máquina de estados con [ScanInput] construidos a mano, que es
 * exactamente lo que hace `ScanSessionTest`.
 */
@Composable
actual fun BarCodeScannerView(
    modifier: Modifier,
    window: ScanWindow,
    isActive: Boolean,
    onScan: (ScanInput) -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF111827)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "La cámara no está disponible en el target JVM.\n" +
                "Usa Android o iOS para escanear.",
            color = Color(0xFF9CA3AF),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}
