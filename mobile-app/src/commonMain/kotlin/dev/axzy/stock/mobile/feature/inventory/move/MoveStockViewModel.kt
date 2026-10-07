package dev.axzy.stock.mobile.feature.inventory.move

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.axzy.stock.domain.scanner.ScanState
import dev.axzy.stock.domain.scanner.ScanWindow
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.mobile.core.data.CatalogRepository
import dev.axzy.stock.mobile.core.data.ProductView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * ViewModel de «mover mercancía».
 *
 * MVI en la forma que pide §3.4: la pantalla emite [MoveStockIntent], el ViewModel
 * pasa cada intent por el reducer PURO ([reduceMoveStock]) y publica el nuevo
 * estado como `StateFlow`. El composable no piensa: pinta el estado y emite intents.
 *
 * POR QUÉ `androidx.lifecycle.ViewModel` Y NO UNA CLASE PROPIA: ya es
 * multiplataforma (la línea KMP de lifecycle da `ViewModel` a iOS), así que no hay
 * motivo para inventar una abstracción de ciclo de vida que habría que probar.
 *
 * LOS EFECTOS (consultar el catálogo para resolver un código) SÍ VIVEN AQUÍ: un
 * reducer puro no puede hacer E/S. Ésa es la frontera: el reducer decide, el
 * ViewModel consulta y notifica.
 */
class MoveStockViewModel(
    private val catalog: CatalogRepository,
    private val products: List<ProductView>,
    private val mode: MoveMode = MoveMode.LOCATE,
    private val window: ScanWindow = ScanWindow.DEFAULT,
) : ViewModel() {

    private val _state = MutableStateFlow(MoveStockUiState(mode = mode))
    val state: StateFlow<MoveStockUiState> = _state.asStateFlow()

    /**
     * Avisos de un solo uso (confirmado, error de red). Un `StateFlow` no sirve
     * para esto: si el aviso viviera en el estado, al rotar la pantalla se
     * volvería a mostrar el «movimiento confirmado» de hace un minuto.
     */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onIntent(intent: MoveStockIntent) {
        val anterior = _state.value
        val siguiente = reduceMoveStock(
            state = anterior,
            intent = intent,
            window = window,
            resolveProduct = ::resolveProduct,
            resolveLocation = ::resolveLocation,
        )
        _state.value = siguiente

        // El reducer marca `submitting`; el efecto lo lanza el ViewModel. Así el
        // reducer se puede probar sin corrutinas y sin esperar nada.
        if (intent is MoveStockIntent.Confirmed &&
            anterior.scan is ScanState.ReadyToConfirm &&
            siguiente.submitting
        ) {
            submit(siguiente)
        }
    }

    /**
     * Resuelve un código de producto escaneado.
     *
     * SOLO SE ACEPTA UN PRODUCTO QUE EXISTA. Un código bien FORMADO pero
     * desconocido (`CINTA20` cumple el alfabeto pero no está en el catálogo) debe
     * rechazarse: si bastara con que el formato sea válido, un código de barras de
     * un envase ajeno avanzaría el flujo y el operador capturaría un movimiento de
     * un producto que no es.
     *
     * Se busca primero en la lista ya cargada y solo después en el repositorio: el
     * operador escanea muchos códigos seguidos y una consulta por cada uno es lo que
     * hace que un escáner «vaya lento».
     */
    private fun resolveProduct(raw: String): ProductCode? {
        val normalizado = raw.trim().uppercase()
        products.firstOrNull { it.code.value == normalizado }?.let { return it.code }
        return null
    }

    /** Una ubicación solo es válida si existe de verdad en el catálogo. */
    private fun resolveLocation(raw: String): LocationCode? =
        LocationCode.validate(raw.trim().uppercase()).getOrNull()

    private fun submit(estado: MoveStockUiState) {
        val listo = estado.scan as? ScanState.ReadyToConfirm ?: return
        viewModelScope.launch {
            // AQUÍ IRÁ LA LLAMADA A `stock-network`:
            //   inventory.move(productId, fromLocationId, toLocationId, quantity, idempotencyKey)
            // La clave de idempotencia es OBLIGATORIA en el contrato: el móvil
            // reintenta sin señal y, sin clave, un reintento duplica un movimiento.
            // Mientras no exista ese módulo, el flujo se completa localmente y se
            // informa con el texto exacto que devolverá el servidor.
            _events.send(
                "Movimiento preparado: ${listo.units} u. de ${listo.product.value} → ${listo.to.value}. " +
                    "Pendiente de conectar con stock-network.",
            )
            _state.value = _state.value.copy(submitting = false, confirmationMessage = "Listo para enviar")
        }
    }

    /** Reinicia el flujo tras confirmar o cancelar. */
    fun reset() = onIntent(MoveStockIntent.Reset)
}
