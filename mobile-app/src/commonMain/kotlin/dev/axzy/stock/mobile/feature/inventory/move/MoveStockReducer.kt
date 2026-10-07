package dev.axzy.stock.mobile.feature.inventory.move

import dev.axzy.stock.domain.scanner.ScanInput
import dev.axzy.stock.domain.scanner.ScanState
import dev.axzy.stock.domain.scanner.ScanWindow
import dev.axzy.stock.domain.scanner.contains
import dev.axzy.stock.domain.scanner.onScan
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MOVER MERCANCÍA — reducer PURO (MVI)
 *
 * POR QUÉ MVI Y NO MVVM AQUÍ (PLAN §3.4):
 * hay una secuencia real que romper (producto → unidades → ubicación →
 * confirmar). Con MVVM «a secas» esa secuencia acaba en variables sueltas
 * (`producto`, `unidades`, `destino`) que se pueden combinar mal: se puede
 * confirmar un movimiento sin destino, o asignar una ubicación antes de tener
 * producto. El legacy lo hizo con un `step` numérico y el resultado fue que la
 * rama que asignaba la ubicación **era inalcanzable** (bug M3) porque el handler
 * comparaba `step === 1` después de incrementarlo.
 *
 * AQUÍ EL ESTADO ES UN TIPO SELLADO DEL DOMINIO y las transiciones las hace
 * `ScanState.onScan`, que vive en `stock-domain`. Consecuencias:
 *   · las transiciones inválidas no se pueden escribir (no hay combinación de
 *     campos que represente «ubicación sin producto»);
 *   · el backend ejecuta el MISMO código, así que móvil y servidor no pueden
 *     divergir;
 *   · el reducer es una función pura y se prueba en JVM sin emulador.
 *
 * ESTE ARCHIVO NO DEPENDE DE COMPOSE NI DE androidx. Es deliberado: si el reducer
 * necesitara el framework para probarse, el test dejaría de ser barato y dejaría
 * de correrse.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Dónde se va a mover la mercancía. */
enum class MoveMode {
    /** De recepción al estante: es un traslado, el total NO cambia. */
    LOCATE,

    /** Entre estantes de la misma bodega. */
    BETWEEN_SHELVES,
    ;

    val title: String get() = when (this) {
        LOCATE -> "Ubicar mercancía"
        BETWEEN_SHELVES -> "Mover entre estantes"
    }
}

/** Estado de la pantalla. Es un `data class` inmutable: el reducer devuelve uno nuevo. */
data class MoveStockUiState(
    val mode: MoveMode = MoveMode.LOCATE,
    /** La máquina de estados del dominio. Es la única fuente de verdad del flujo. */
    val scan: ScanState = ScanState.Idle,
    val unitsText: String = "",
    /** Unidades ya validadas por el dominio, si las hay. */
    val units: Long? = null,
    val unitsError: String? = null,
    /** Aviso de rechazo del escáner, ya redactado para el operador. */
    val rejection: String? = null,
    /** Si el último código cayó dentro de la ventana. Alimenta el marco de la máscara. */
    val positionValid: Boolean? = null,
    val submitting: Boolean = false,
    /** Resultado de la confirmación. `null` mientras no se ha confirmado. */
    val confirmationMessage: String? = null,
) {
    /** El botón de confirmar solo se habilita con el flujo completo. */
    val canConfirm: Boolean get() = scan is ScanState.ReadyToConfirm && !submitting

    /** El paso lo dicta el DOMINIO, no un contador local. */
    val step: Int get() = scan.step
}

/** Acciones que la pantalla puede emitir. Selladas: no hay acciones «sueltas». */
sealed interface MoveStockIntent {
    /** Una lectura de la cámara, con su posición normalizada. */
    data class Scanned(val input: ScanInput) : MoveStockIntent

    /** El operador tecleó las unidades (puede ser texto inválido todavía). */
    data class UnitsTyped(val text: String) : MoveStockIntent

    /** El operador confirmó el movimiento. El efecto de red lo hace el ViewModel. */
    data object Confirmed : MoveStockIntent

    /** Cerrar el aviso de rechazo sin tocar el avance del flujo. */
    data object DismissRejection : MoveStockIntent

    /** Empezar de cero. */
    data object Reset : MoveStockIntent

    /** Limpiar el mensaje de confirmación (tras mostrarlo una vez). */
    data object ClearConfirmation : MoveStockIntent
}

/**
 * El reducer. **Función pura**: mismos argumentos, mismo resultado, sin efectos.
 *
 * No lanza excepciones y no toca el mundo: eso es lo que permite probar los caos
 * raros (escanear fuera de ventana, confirmar sin unidades, teclear «abc» en las
 * unidades) en milisegundos.
 *
 * @param window la ventana de validación del dominio. Entra como parámetro para que
 *   el test pueda usar una ventana distinta y para que la pantalla y la validación
 *   compartan SIEMPRE la misma instancia.
 */
fun reduceMoveStock(
    state: MoveStockUiState,
    intent: MoveStockIntent,
    window: ScanWindow = ScanWindow.DEFAULT,
    resolveProduct: (String) -> ProductCode?,
    resolveLocation: (String) -> LocationCode?,
): MoveStockUiState = when (intent) {

    is MoveStockIntent.Scanned -> {
        // La posición se comprueba con la MISMA función que usa `onScan`. Repetirla
        // aquí con otra fórmula sería reintroducir el bug M4 por la puerta de atrás:
        // dos validaciones de la misma ventana que pueden discrepar.
        val dentroDeVentana = window.contains(intent.input.bounds)

        // Las unidades tecleadas se pasan al dominio cuando existen: la regla «no se
        // puede asignar ubicación sin unidades» es DEL DOMINIO, no de esta pantalla.
        val unidades = state.units

        val outcome = state.scan.onScan(
            input = intent.input,
            window = window,
            resolveProduct = resolveProduct,
            resolveLocation = resolveLocation,
            unitsEntered = unidades,
        )

        when (outcome) {
            is dev.axzy.stock.domain.scanner.ScanOutcome.Advanced -> state.copy(
                scan = outcome.state,
                rejection = null,
                positionValid = dentroDeVentana,
            )

            is dev.axzy.stock.domain.scanner.ScanOutcome.Rejected -> state.copy(
                rejection = outcome.reason,
                positionValid = dentroDeVentana,
                // El estado del dominio NO se sustituye por un `Failed`: el flujo se
                // queda donde estaba y el operador reintenta. Envolverlo obligaría a
                // «deshacer» el error para continuar, y ese deshacer es exactamente el
                // camino por el que el legacy volvía al paso 0.
            )
        }
    }

    is MoveStockIntent.UnitsTyped -> {
        val texto = intent.text.filter { it.isDigit() }
        if (texto.isEmpty()) {
            state.copy(unitsText = "", units = null, unitsError = null)
        } else {
            val valor = texto.toLongOrNull()
            when {
                valor == null -> state.copy(
                    unitsText = texto,
                    units = null,
                    unitsError = "Escribe un número de unidades",
                )

                valor <= 0L -> state.copy(
                    unitsText = texto,
                    units = null,
                    unitsError = "Las unidades deben ser mayores que cero",
                )

                else -> state.copy(unitsText = texto, units = valor, unitsError = null)
            }
        }
    }

    is MoveStockIntent.Confirmed -> {
        // Se revalida aquí aunque el botón solo se habilite con `canConfirm`: un
        // reducer que confía en que la UI no le va a mandar algo inválido no es un
        // reducer, es una esperanza.
        if (state.scan !is ScanState.ReadyToConfirm) state
        else state.copy(submitting = true)
    }

    is MoveStockIntent.DismissRejection -> state.copy(rejection = null)

    is MoveStockIntent.Reset -> MoveStockUiState(mode = state.mode)

    is MoveStockIntent.ClearConfirmation -> state.copy(confirmationMessage = null, submitting = false)
}
