package dev.axzy.stock.mobile.core.camera

import dev.axzy.stock.domain.scanner.ScanInput
import dev.axzy.stock.domain.scanner.ScanOutcome
import dev.axzy.stock.domain.scanner.ScanState
import dev.axzy.stock.domain.scanner.ScanTarget
import dev.axzy.stock.domain.scanner.ScanWindow
import dev.axzy.stock.domain.scanner.contains
import dev.axzy.stock.domain.scanner.onScan
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SESIÓN DE ESCANEO — el pegamento entre la cámara y la máquina de estados
 *
 * Sustituye al `step` numérico suelto del legacy (bug M3: se incrementaba una vez
 * y el handler comparaba `step === 1`, así que la rama que asignaba la ubicación
 * **era inalcanzable**).
 *
 * AQUÍ NO HAY NINGUNA TRANSICIÓN DE ESTADO. Este objeto solo:
 *   1. filtra la repetición de fotogramas ([ScanThrottle]),
 *   2. delega la transición en `ScanState.onScan` **del dominio**,
 *   3. expone el estado y el último rechazo para que la UI los pinte.
 *
 * La razón de que la transición no esté aquí es la de §3.2-bis: el backend
 * ejecuta el MISMO `onScan`, así que no puede haber una versión web/móvil del
 * flujo y otra del servidor. Si la lógica viviera en este archivo, el móvil
 * tendría su propia idea de cuándo se puede asignar una ubicación.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ScanSession(
    private val window: ScanWindow = ScanWindow.DEFAULT,
    private val resolveProduct: (String) -> ProductCode?,
    private val resolveLocation: (String) -> LocationCode?,
    private val nowMs: () -> Long = { 0L },
    throttleIntervalMs: Long = 2_500L,
) {
    private val throttle = ScanThrottle(throttleIntervalMs)

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** Último rechazo, ya redactado para el operador. Se limpia al avanzar. */
    private val _lastRejection = MutableStateFlow<String?>(null)
    val lastRejection: StateFlow<String?> = _lastRejection.asStateFlow()

    /**
     * Si el último código detectado cayó dentro de la ventana efectiva.
     * `null` = todavía no hay lectura. Alimenta el color del marco de la máscara.
     */
    private val _positionValid = MutableStateFlow<Boolean?>(null)

    /** Nombre corto porque la pantalla lo lee en cada fotograma. */
    val positionValid: StateFlow<Boolean?> = _positionValid.asStateFlow()

    /** Lo que el operador debe escanear ahora, para la instrucción en pantalla. */
    val expected: ScanTarget get() = _state.value.expected

    /** Paso actual, **solo para mostrar**. Nunca para decidir: para eso está el tipo. */
    val step: Int get() = _state.value.step

    /**
     * Procesa una lectura de la cámara.
     *
     * @param unitsEntered unidades tecleadas por el operador, si las hay. Se pasan
     *   al dominio porque la regla «no se puede asignar ubicación sin unidades» es
     *   del dominio, no de la pantalla.
     */
    fun onCameraScan(input: ScanInput, unitsEntered: Long? = null) {
        if (!throttle.shouldEmit(input.raw, nowMs())) return

        // La comprobación de posición se hace con la MISMA función del dominio que
        // usa `onScan`. Repetirla aquí con otra fórmula sería reintroducir el bug
        // M4 por la puerta de atrás: dos validaciones que pueden discrepar.
        _positionValid.value = window.contains(input.bounds)

        val outcome = _state.value.onScan(
            input = input,
            window = window,
            resolveProduct = resolveProduct,
            resolveLocation = resolveLocation,
            unitsEntered = unitsEntered,
        )

        when (outcome) {
            is ScanOutcome.Advanced -> {
                _state.value = outcome.state
                _lastRejection.value = null
            }

            is ScanOutcome.Rejected -> {
                _lastRejection.value = outcome.reason
                // El estado NO se mueve y NO se envuelve en ScanState.Failed: si se
                // envolviera, el reintento tendría que deshacerlo y ese "deshacer"
                // es justo el camino por el que el legacy volvía al paso 0.
            }
        }
    }

    /** Vuelve al inicio. Se usa al cerrar la pantalla o al cancelar el flujo. */
    fun reset() {
        _state.value = ScanState.Idle
        _lastRejection.value = null
        _positionValid.value = null
        throttle.reset()
    }

    /** Limpia solo el aviso, sin tocar el avance del flujo. */
    fun dismissRejection() {
        _lastRejection.value = null
    }
}
