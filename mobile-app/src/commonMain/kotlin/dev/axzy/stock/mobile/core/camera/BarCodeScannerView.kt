package dev.axzy.stock.mobile.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.axzy.stock.domain.scanner.ScanInput

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ESCÁNER DE CÓDIGOS — contrato común
 *
 * Aquí vive **solo lo que es idéntico en Android e iOS**: el contrato del
 * componente y el tipo que se le entrega. La cámara es de plataforma
 * (`expect/actual`), pero **qué se hace con lo escaneado no lo es**, y por eso
 * vive en `stock-domain` (§3.2-bis) y no aquí.
 *
 * EL DEFECTO DEL LEGACY QUE ESTO EVITA (bug M4)
 *
 * El escáner del legacy tenía la **máscara visual de 300×100** y la **validación
 * de 250×50**, en dos archivos distintos. Resultado: el operador centraba el
 * código dentro de la banda que veía y el escáner lo descartaba sin explicar por
 * qué.
 *
 * La causa raíz no era el tamaño elegido: era que había **dos definiciones de la
 * misma ventana**. Aquí hay una sola, `ScanWindow.DEFAULT` de `stock-domain`, y
 * las dos plataformas la pasan a este contrato. La máscara que se pinta
 * ([ScanMaskOverlay]) y la validación ([dev.axzy.stock.domain.scanner.contains])
 * leen **el mismo objeto**: desalinearlas exigiría cambiar el código para que
 * dejaran de usar la misma variable.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Vista de cámara con lectura de códigos.
 *
 * @param windowLa ventana de validación, tal cual viene del dominio. Se recibe
 *   como parámetro —y no se lee dentro del `actual`— precisamente para que la
 *   implementación de plataforma no pueda tener su propia idea del tamaño: si
 *   necesita la ventana, se la dan.
 * @param isActive pausa la cámara cuando la pantalla deja de estar en primer
 *   plano o cuando ya hay un resultado. Sin esto, el escáner sigue leyendo detrás
 *   del modal de confirmación y captura un segundo código «fantasma», que es un
 *   movimiento de inventario que nadie ordenó.
 * @param onScan entrega el código **crudo y su posición normalizada**. El
 *   consumidor decide: validar la posición contra la ventana, resolver el código
 *   contra el catálogo y avanzar la máquina de estados. Aquí no se valida nada,
 *   porque validar en la plataforma es exactamente como el legacy acabó con dos
 *   ventanas distintas.
 */
@Composable
expect fun BarCodeScannerView(
    modifier: Modifier,
    window: dev.axzy.stock.domain.scanner.ScanWindow,
    isActive: Boolean,
    onScan: (ScanInput) -> Unit,
)

/**
 * Filtro de repeticiones: ML Kit y Vision entregan el mismo código en cada
 * fotograma mientras siga enfocado. Sin filtrar, un segundo de enfoque captura
 * 30 «escaneos».
 *
 * POR QUÉ ESTÁ EN COMMONMAIN Y NO EN CADA PLATAFORMA: es una regla de negocio
 * delgada (qué cuenta como un escaneo nuevo) y, si viviera en los dos `actual`,
 * Android e iOS se comportarían distinto ante el mismo gesto. Es lógica pura, así
 * que se prueba en JVM sin cámara ni emulador.
 */
class ScanThrottle(
    private val sameCodeIntervalMs: Long = 2_500L,
) {
    private var lastCode: String? = null
    private var lastAtMs: Long = Long.MIN_VALUE

    /**
     * Devuelve `true` si el código debe entregarse.
     *
     * Se acepta el mismo código tras [sameCodeIntervalMs]: el operador puede
     * necesitar escanear el MISMO producto dos veces seguidas (dos líneas
     * distintas del mismo artículo) y bloquearlo para siempre sería un defecto
     * nuevo a cambio de arreglar otro.
     */
    fun shouldEmit(code: String, nowMs: Long): Boolean {
        val previous = lastCode
        val previousAt = lastAtMs
        val repetido = previous == code && nowMs - previousAt < sameCodeIntervalMs
        if (repetido) return false
        lastCode = code
        lastAtMs = nowMs
        return true
    }

    /** Reinicia el filtro al cerrar o reabrir el escáner. */
    fun reset() {
        lastCode = null
        lastAtMs = Long.MIN_VALUE
    }
}
