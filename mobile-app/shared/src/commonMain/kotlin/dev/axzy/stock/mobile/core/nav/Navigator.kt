package dev.axzy.stock.mobile.core.nav

import dev.axzy.stock.mobile.feature.inventory.move.MoveMode

/**
 * Pantallas de la app, como tipo sellado.
 *
 * POR QUÉ UN TIPO SELLADO Y NO RUTAS DE TEXTO: con cadenas, un `navigate("facturas")`
 * mal escrito compila y falla en tiempo de ejecución, cuando el operador ya está en
 * el pasillo. Con un tipo sellado, el `when` de la navegación es exhaustivo y el
 * compilador avisa de la pantalla que falta.
 *
 * El orden de esta lista es el orden de implementación de la Fase 6: auth/config →
 * home → facturas → órdenes → ubicaciones → inventario → etiquetas.
 */
sealed interface Screen {

    /** Inicio de sesión. */
    data object Login : Screen

    /** Menú con las categorías y sus contadores. */
    data object Home : Screen

    // ── Recepción ───────────────────────────────────────────────────────────
    data object Invoices : Screen
    data class InvoiceDetail(val invoiceId: Long) : Screen
    data class InvoiceCapture(val invoiceId: Long, val lineId: Long) : Screen

    // ── Surtido ─────────────────────────────────────────────────────────────
    data object Orders : Screen
    data class OrderDetail(val orderId: Long) : Screen

    // ── Ubicaciones ─────────────────────────────────────────────────────────
    data object Locations : Screen
    data class LocationContent(val locationId: Long) : Screen

    // ── Inventario ──────────────────────────────────────────────────────────
    data class InventoryMove(val mode: MoveMode) : Screen
    data object InventorySearch : Screen

    // ── Etiquetas ───────────────────────────────────────────────────────────
    data object Labels : Screen
    data object PrintJobs : Screen
}

/**
 * Navegador mínimo: una pila de pantallas en un `StateFlow`.
 *
 * POR QUÉ NO `androidx.navigation` TODAVÍA: el plan lo contempla, pero la versión
 * multiplataforma estable añade una dependencia más que verificar y esta pila cubre
 * lo que hay (16 destinos sin deep links). Cuando lleguen los enlaces profundos y el
 * «volver» del sistema, se sustituye **esta clase** y ninguna pantalla cambia,
 * porque las pantallas no navegan: emiten.
 */
class Navigator(private val initial: Screen = Screen.Login) {

    private val stack = mutableListOf(initial)

    private val _current = kotlinx.coroutines.flow.MutableStateFlow(initial)
    val current: kotlinx.coroutines.flow.StateFlow<Screen> = _current

    val canGoBack: Boolean get() = stack.size > 1

    fun goTo(screen: Screen) {
        stack.add(screen)
        _current.value = screen
    }

    /**
     * Sustituye la pantalla actual. Se usa tras iniciar sesión: volver al login con
     * el botón «atrás» después de entrar es un defecto clásico, y en un dispositivo
     * de almacén se traduce en que el operador cree que se le cerró la sesión.
     */
    fun replace(screen: Screen) {
        stack.removeAt(stack.lastIndex)
        stack.add(screen)
        _current.value = screen
    }

    /** Reinicia la pila. Se usa al cerrar sesión. */
    fun resetTo(screen: Screen) {
        stack.clear()
        stack.add(screen)
        _current.value = screen
    }

    fun back() {
        if (stack.size <= 1) return
        stack.removeAt(stack.lastIndex)
        _current.value = stack.last()
    }
}
