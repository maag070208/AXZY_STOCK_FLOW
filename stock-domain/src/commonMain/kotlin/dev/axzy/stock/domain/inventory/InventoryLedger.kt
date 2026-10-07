package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.Quantity.Companion.ofScaled

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL LIBRO DE INVENTARIO — modelo en memoria, puro y determinista
 *
 * Es la pieza que permite verificar las **invariantes I1–I10** con property-based
 * testing: se generan secuencias aleatorias de operaciones válidas y se afirma
 * que las propiedades se mantienen. Así se cubren combinaciones que a nadie se
 * le ocurriría escribir a mano.
 *
 * **Decisiones de diseño, y por qué:**
 *
 *  1. **El ledger es append-only.** Las entradas nunca se modifican ni se
 *     borran. Es lo contrario de lo que hacía el legacy en
 *     `removeUnitsFromKardex`, que hacía `kardex.update` sobre una fila
 *     histórica y con ello destruía la trazabilidad (hallazgo R-02).
 *
 *  2. **El stock por ubicación es la fuente de verdad y el total es su suma.**
 *     En el legacy era al revés: un solo número total (`Store.stock`) del que
 *     se intentaba deducir la ubicación, compensando con restas. De ahí salió
 *     el bug del doble descuento al ubicar (hallazgo R-01).
 *
 *  3. **No hay ninguna operación que reste del total.** El total solo cambia
 *     por `RECEIPT` (suma) y por `ADJUSTMENT_OUT` (resta, y exige motivo).
 *     Todo lo demás es un traslado entre ubicaciones o hacia el tránsito.
 *
 *  4. **El tránsito es explícito.** La mercancía que viaja entre bodegas sigue
 *     contando en el inventario total. Si se modelara como dos movimientos
 *     instantáneos, el total bajaría durante el trayecto y los reportes
 *     mentirían.
 *
 * **Alcance de la Fase 0:** este modelo es la especificación ejecutable del
 * dominio. La implementación en PostgreSQL con Exposed llega en la Fase 1 y
 * debe reproducir exactamente estas transiciones.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Una entrada del ledger. Inmutable: una vez escrita, no se toca. */
data class LedgerEntry(
    /** Número de secuencia. Es el orden total del ledger. */
    val sequence: Long,
    val productCode: String,
    val type: MovementType,
    val quantity: Quantity,
    val from: LocationCode?,
    val to: LocationCode?,
    /** Cantidad total del producto **después** de aplicar esta entrada (I4). */
    val quantityAfter: Quantity,
    /** Motivo, obligatorio en los ajustes (I6). */
    val reason: String? = null,
    /** Identificador del cliente, para la idempotencia del móvil (I8). */
    val clientUuid: String? = null,
)

/** Saldo de un producto en una ubicación concreta. */
data class LocationBalance(
    val productCode: String,
    val location: LocationCode,
    val quantity: Quantity,
)

/** Mercancía que salió de una bodega y todavía no entró en la otra. */
data class InTransitBalance(
    val productCode: String,
    val fromLocation: LocationCode,
    val toWarehouseId: Long,
    val quantity: Quantity,
)

/**
 * El libro de inventario.
 *
 * Es mutable **solo a través de sus operaciones**, que validan antes de
 * escribir. No expone setters: el estado solo cambia por movimientos, igual
 * que en la base de datos, donde el kardex es append-only.
 */
class InventoryLedger {

    private val _entries = mutableListOf<LedgerEntry>()
    private val balances = mutableMapOf<LocationCode, MutableMap<String, Quantity>>()
    private val _inTransit = mutableListOf<InTransitBalance>()
    private var nextSequence = 1L

    /** El ledger completo, en orden. Es de solo lectura desde fuera. */
    val entries: List<LedgerEntry> get() = _entries.toList()

    /** Mercancía en tránsito entre bodegas. */
    val inTransit: List<InTransitBalance> get() = _inTransit.toList()

    // ═════════════════════════════════════════════════════════════════════════
    // CONSULTAS
    // ═════════════════════════════════════════════════════════════════════════

    /** Cantidad de un producto en una ubicación concreta. */
    fun quantityAt(productCode: String, location: LocationCode): Quantity =
        balances[location]?.get(productCode) ?: Quantity.ZERO

    /**
     * **Total del producto = suma de ubicaciones + tránsito.**
     *
     * Es la definición que hace imposible el bug del legacy. No es un número
     * almacenado que haya que mantener sincronizado: se deriva.
     */
    fun totalOf(productCode: String): Quantity {
        val enUbicaciones = balances.values.sumOf { porProducto ->
            (porProducto[productCode] ?: Quantity.ZERO).value
        }
        val enTransito = _inTransit
            .filter { it.productCode == productCode }
            .sumOf { it.quantity.value }
        return ofScaled(enUbicaciones + enTransito)
    }

    /** Total del producto sumando **solo** las ubicaciones, sin el tránsito. */
    fun totalAtLocationsOf(productCode: String): Quantity =
        ofScaled(
            balances.values.sumOf { (it[productCode] ?: Quantity.ZERO).value }
        )

    /** Total en tránsito del producto. */
    fun totalInTransitOf(productCode: String): Quantity =
        ofScaled(
            _inTransit.filter { it.productCode == productCode }.sumOf { it.quantity.value }
        )

    /**
     * Suma algebraica de todas las entradas del ledger para un producto (I1).
     * Cada entrada declara su efecto en el total: solo `RECEIPT` suma y
     * `ADJUSTMENT_OUT` resta.
     */
    fun ledgerNetOf(productCode: String): Quantity =
        ofScaled(
            _entries.filter { it.productCode == productCode }
                .sumOf { entry ->
                    when (entry.type) {
                        MovementType.RECEIPT_IN -> entry.quantity.value
                        MovementType.ADJUSTMENT_OUT -> -entry.quantity.value
                        MovementType.ADJUSTMENT_IN -> entry.quantity.value
                        MovementType.INITIAL -> entry.quantity.value
                        else -> 0L
                    }
                }
        )

    /** Todos los saldos, para recálculos (I5). */
    fun allBalances(): List<LocationBalance> =
        balances.flatMap { (location, porProducto) ->
            porProducto.map { (productCode, quantity) ->
                LocationBalance(productCode, location, quantity)
            }
        }

    // ═════════════════════════════════════════════════════════════════════════
    // OPERACIONES
    // ═════════════════════════════════════════════════════════════════════════

    /** Carga inicial del catálogo. Entra mercancía por primera vez. */
    fun initialLoad(
        productCode: String,
        location: LocationCode,
        quantity: Quantity,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity(
                "La carga inicial debe ser mayor que cero (se recibió $quantity)"
            ).asLeft()
        }
        val resultado = record(productCode, MovementType.INITIAL, quantity, from = null, to = location)
        if (resultado is Either.Left<*>) return resultado

        addToLocation(productCode, location, quantity)
        return resultado
    }

    /** Recepción de factura: entra mercancía nueva. Es uno de los dos que suman. */
    fun receipt(
        productCode: String,
        receivingLocation: LocationCode,
        quantity: Quantity,
        clientUuid: String? = null,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("La recepción debe ser mayor que cero").asLeft()
        }

        // ── Invariante I8: idempotencia ─────────────────────────────────────
        // Repetir la misma clave no duplica el movimiento. Es lo que permite al
        // móvil reintentar una sincronización sin duplicar stock.
        if (clientUuid != null && _entries.any { it.clientUuid == clientUuid }) {
            return DomainError.Duplicate(
                entity = "movimiento",
                value = clientUuid,
                message = "Este movimiento ya fue registrado (idempotencia)",
            ).asLeft()
        }

        val resultado = record(
            productCode, MovementType.RECEIPT_IN, quantity,
            from = null, to = receivingLocation, clientUuid = clientUuid,
        )
        if (resultado is Either.Left<*>) return resultado
        addToLocation(productCode, receivingLocation, quantity)
        return resultado
    }

    /**
     * **Ubicar** mercancía recibida en su estante.
     *
     * Es el movimiento del bug del legacy: aquí se restaba del total, dos veces
     * de hecho. En este modelo es un **traslado**: sale de la ubicación de
     * recepción y entra al estante, y **el total no cambia** (decisión R-65).
     */
    fun locate(
        productCode: String,
        fromReceiving: LocationCode,
        toShelf: LocationCode,
        quantity: Quantity,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("La cantidad a ubicar debe ser mayor que cero").asLeft()
        }
        if (fromReceiving == toShelf) {
            return DomainError.SameSourceAndDestination(fromReceiving.value).asLeft()
        }

        val disponible = quantityAt(productCode, fromReceiving)
        if (quantity > disponible) {
            return DomainError.InsufficientStock(
                available = disponible.value,
                requested = quantity.value,
                location = fromReceiving.value,
            ).asLeft()
        }

        val resultado = record(
            productCode, MovementType.LOCATE, quantity,
            from = fromReceiving, to = toShelf,
        )
        if (resultado is Either.Left<*>) return resultado
        addToLocation(productCode, fromReceiving, ofScaled(-quantity.value))
        addToLocation(productCode, toShelf, quantity)
        return resultado
    }

    /** Traslado entre estantes de la misma bodega. Total invariante. */
    fun moveWithinWarehouse(
        productCode: String,
        from: LocationCode,
        to: LocationCode,
        quantity: Quantity,
        clientUuid: String? = null,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("La cantidad a mover debe ser mayor que cero").asLeft()
        }
        if (from == to) {
            return DomainError.SameSourceAndDestination(from.value).asLeft()
        }
        if (clientUuid != null && _entries.any { it.clientUuid == clientUuid }) {
            return DomainError.Duplicate("movimiento", clientUuid, "Movimiento ya registrado").asLeft()
        }

        val disponible = quantityAt(productCode, from)
        if (quantity > disponible) {
            return DomainError.InsufficientStock(disponible.value, quantity.value, from.value).asLeft()
        }

        val resultado = record(
            productCode, MovementType.MOVE_WITHIN_WAREHOUSE, quantity,
            from = from, to = to, clientUuid = clientUuid,
        )
        if (resultado is Either.Left<*>) return resultado
        addToLocation(productCode, from, ofScaled(-quantity.value))
        addToLocation(productCode, to, quantity)
        return resultado
    }

    /** Salida de una bodega hacia otra: la mercancía entra en tránsito. */
    fun transferOut(
        productCode: String,
        fromLocation: LocationCode,
        toWarehouseId: Long,
        quantity: Quantity,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("La cantidad a transferir debe ser mayor que cero").asLeft()
        }

        val disponible = quantityAt(productCode, fromLocation)
        if (quantity > disponible) {
            return DomainError.InsufficientStock(
                disponible.value, quantity.value, fromLocation.value,
            ).asLeft()
        }

        val resultado = record(
            productCode, MovementType.TRANSFER_OUT, quantity,
            from = fromLocation, to = null,
        )
        if (resultado is Either.Left<*>) return resultado
        addToLocation(productCode, fromLocation, ofScaled(-quantity.value))
        // Entra en tránsito: sigue contando en el total.
        _inTransit.add(
            InTransitBalance(productCode, fromLocation, toWarehouseId, quantity)
        )
        return resultado
    }

    /** Llegada desde el tránsito: entra al estante destino y sale del tránsito. */
    fun transferIn(
        productCode: String,
        toLocation: LocationCode,
        fromWarehouseId: Long,
        quantity: Quantity,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("La cantidad a recibir debe ser mayor que cero").asLeft()
        }

        val indice = _inTransit.indexOfFirst {
            it.productCode == productCode &&
                it.toWarehouseId == toLocation.warehouseHint &&
                it.quantity >= quantity
        }
        if (indice < 0) {
            return DomainError.InsufficientStock(
                available = 0L,
                requested = quantity.value,
                location = "tránsito",
                message = "No hay mercancía de $productCode en tránsito para recibir",
            ).asLeft()
        }

        val resultado = record(
            productCode, MovementType.TRANSFER_IN, quantity,
            from = null, to = toLocation,
        )
        if (resultado is Either.Left<*>) return resultado
        val actual = _inTransit[indice]
        _inTransit[indice] = actual.copy(quantity = actual.quantity - quantity)
        if (_inTransit[indice].quantity.isZero) _inTransit.removeAt(indice)
        addToLocation(productCode, toLocation, quantity)
        return resultado
    }

    /** Ajuste positivo autorizado. Requiere motivo (I6). */
    fun adjustmentIn(
        productCode: String,
        location: LocationCode,
        quantity: Quantity,
        reason: String,
    ): DomainResult<LedgerEntry> {
        if (reason.isBlank()) {
            return DomainError.ReasonRequired("un ajuste de entrada").asLeft()
        }
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("El ajuste debe ser mayor que cero").asLeft()
        }
        val resultado = record(
            productCode, MovementType.ADJUSTMENT_IN, quantity,
            from = null, to = location, reason = reason,
        )
        if (resultado is Either.Left<*>) return resultado
        addToLocation(productCode, location, quantity)
        return resultado
    }

    /** Merma: sale mercancía de forma definitiva. Es el otro que resta. */
    fun adjustmentOut(
        productCode: String,
        location: LocationCode,
        quantity: Quantity,
        reason: String,
    ): DomainResult<LedgerEntry> {
        if (reason.isBlank()) {
            return DomainError.ReasonRequired("una merma").asLeft()
        }
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("La merma debe ser mayor que cero").asLeft()
        }

        val disponible = quantityAt(productCode, location)
        if (quantity > disponible) {
            return DomainError.InsufficientStock(
                disponible.value, quantity.value, location.value,
            ).asLeft()
        }

        val resultado = record(
            productCode, MovementType.ADJUSTMENT_OUT, quantity,
            from = location, to = null, reason = reason,
        )
        if (resultado is Either.Left<*>) return resultado
        addToLocation(productCode, location, ofScaled(-quantity.value))
        return resultado
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNO
    // ═════════════════════════════════════════════════════════════════════════

    private fun record(
        productCode: String,
        type: MovementType,
        quantity: Quantity,
        from: LocationCode?,
        to: LocationCode?,
        reason: String? = null,
        clientUuid: String? = null,
    ): DomainResult<LedgerEntry> {
        // `quantityAfter` se calcula con la suma de ubicaciones más tránsito
        // ANTES de aplicar el efecto, para que el ledger sea auto-auditable (I4).
        val antes = totalOf(productCode)
        val despues = when (type) {
            MovementType.RECEIPT_IN, MovementType.ADJUSTMENT_IN, MovementType.INITIAL -> antes + quantity
            MovementType.ADJUSTMENT_OUT -> antes - quantity
            else -> antes
        }

        val entry = LedgerEntry(
            sequence = nextSequence++,
            productCode = productCode,
            type = type,
            quantity = quantity,
            from = from,
            to = to,
            quantityAfter = despues,
            reason = reason,
            clientUuid = clientUuid,
        )
        _entries.add(entry)
        return entry.asRight()
    }

    private fun addToLocation(productCode: String, location: LocationCode, delta: Quantity) {
        val porProducto = balances.getOrPut(location) { mutableMapOf() }
        val actual = porProducto[productCode] ?: Quantity.ZERO
        porProducto[productCode] = actual + delta
    }
}

/**
 * Pista de bodega derivada del código de ubicación.
 *
 * En el modelo real la ubicación tiene una FK a la bodega. Aquí, para el
 * property test, se deriva del prefijo del código: `NORTE-01` → bodega 1,
 * `SUR-01` → bodega 2. Mantiene el modelo de test sin duplicar el esquema.
 */
val LocationCode.warehouseHint: Long
    get() = when {
        value.startsWith("NORTE") -> 1L
        value.startsWith("SUR") -> 2L
        else -> 0L
    }
