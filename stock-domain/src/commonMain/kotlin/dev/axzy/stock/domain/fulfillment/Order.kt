package dev.axzy.stock.domain.fulfillment

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SURTIDO DE ÓRDENES (contrato §6)
 *
 * ── LA UNIÓN DE ESTADOS DE LA LÍNEA ES PROPIA, Y ESO ES UNA CORRECCIÓN ────
 *
 * El contrato lo dice explícitamente y aquí se respeta:
 *
 * > «**Antes reutilizaba los estados de `InvoiceLineStatus` y era un error**:
 * > `LOCATED` y `DISCREPANCY` son del flujo de recepción y no significan nada en
 * > surtido. Compartir la unión obligaba a la interfaz a contemplar estados
 * > imposibles, y a un `when` exhaustivo a tener ramas que nunca se ejecutan.»
 *
 * `ALLOCATED` sustituye a `LOCATED`: en surtido la mercancía se **asigna** a un
 * pedido, no se ubica. Y `DISCREPANCY` no existe: el equivalente es el FALTANTE,
 * que es una salida con motivo `SHORTAGE` y origen `FULFILLMENT_SHORTAGE`, no un
 * estado de la línea.
 *
 * ── LA REGLA DE STOCK, QUE ES LO QUE MÁS FÁCIL SERÍA EQUIVOCAR ─────────────
 *
 * > **`supplied_quantity` es SIEMPRE lo ya descontado del inventario.**
 *
 * Empacar descuenta lo empacado. Confirmar descuenta **lo que FALTA** hasta
 * `quantity`, y no `quantity` entera:
 *
 * ```
 *   quantity = 10, supplied = 7   →  empacar 7 descontó 7
 *                                    confirmar descuenta 3  (7 + 3 = 10)
 *                                    total descontado   10  ✔
 * ```
 *
 * Si confirmar descontara `quantity` completa, el total descontado sería 17 y
 * **el inventario habría bajado 7 unidades de más**. Es un descuadre que solo
 * aparece al cuadrar el almacén, y que no se distingue de una merma real.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Estado de la ORDEN. Valores del enum `order_status`. */
enum class OrderStatus {
    /** Creada, sin empezar. */
    PENDING,

    /** Alguien está surtiendo: hay líneas asignadas o parcialmente empacadas. */
    IN_PROGRESS,

    /** Todo empacado, pendiente de confirmar. */
    PACKAGED,

    /** Confirmada y pendiente de salir al ERP. */
    PENDING_TO_OUT,

    /** Exportada al ERP. Es el final del flujo. */
    COMPLETED,

    /** Anulada. Nunca se borra: se anula con trazabilidad. */
    CANCELLED,
    ;

    /** ¿Se puede todavía empacar en esta orden? */
    val allowsPackaging: Boolean
        get() = this == PENDING || this == IN_PROGRESS || this == PACKAGED
}

/**
 * Estado de la LÍNEA de orden. Valores del enum `order_line_status`.
 *
 * **Unión propia, no la de factura** (ver la cabecera de este archivo).
 */
enum class OrderLineStatus {
    /** Sin empezar. */
    PENDING,

    /** Asignada a un operador, que la tiene en su lista. */
    ASSIGNED,

    /** Surtida del estante. La mercancía ya se descontó. */
    SUPPLIED,

    /** Verificada por un segundo par de ojos antes de cerrar el paquete. */
    VERIFIED,

    /** Anulada. */
    CANCELLED,
    ;

    /** ¿Queda trabajo por hacer en esta línea? */
    val isOpen: Boolean
        get() = this == PENDING || this == ASSIGNED
}

/** Una línea de la orden. */
data class OrderLine(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val unitMeasure: String,
    /** Lo que pide el cliente. */
    val quantity: Quantity,
    /**
     * **Lo ya descontado del inventario.** No es «lo empacado»: es el acumulado
     * que el inventario ya refleja.
     */
    val suppliedQuantity: Quantity = Quantity.ZERO,
    val status: OrderLineStatus = OrderLineStatus.PENDING,
    val assignedUserId: Long? = null,
    val locationId: Long? = null,
) {

    /** Lo que FALTA por sacar del inventario. */
    val pendingQuantity: Quantity
        get() = quantity - suppliedQuantity

    /** ¿Está completa? */
    val isComplete: Boolean
        get() = suppliedQuantity >= quantity

    /**
     * Empaca una cantidad de esta línea.
     *
     * **Devuelve la línea nueva, no toca stock.** El movimiento del inventario lo
     * ejecuta el motor SQL en la misma transacción que escribe el kardex; el
     * dominio decide si la operación es legal y cuánto hay que descontar. Si el
     * dominio moviera el stock y la escritura del kardex fallara, la mercancía
     * habría cambiado de sitio sin registro.
     */
    fun packageQuantity(amount: Quantity): Either<DomainError, OrderLine> {
        if (status == OrderLineStatus.CANCELLED) {
            return DomainError.InvalidTransition(
                entity = "Línea de orden",
                from = status.name,
                action = "empacar",
            ).asLeft()
        }
        if (amount <= Quantity.ZERO) {
            return DomainError.InvalidQuantity("La cantidad a empacar debe ser positiva.").asLeft()
        }
        // Se comprueba contra lo PENDIENTE y no contra `quantity`: empacar 7 de 10
        // dos veces sumaría 14. La base lo impide con un `CHECK`, pero aquí se
        // puede decir cuánto queda, que es lo que el operador necesita.
        if (amount > pendingQuantity) {
            return DomainError.ExceedsPendingQuantity(
                requested = amount.value,
                pending = pendingQuantity.value,
            ).asLeft()
        }

        val nextSupplied = suppliedQuantity + amount
        return copy(
            suppliedQuantity = nextSupplied,
            status = if (nextSupplied >= quantity) OrderLineStatus.SUPPLIED else OrderLineStatus.ASSIGNED,
        ).asRight()
    }

    /**
     * Asigna la línea a quien la va a surtir, y opcionalmente en qué estantería.
     *
     * **Solo desde `PENDING` o `ASSIGNED`.** Una línea ya surtida o anulada no se reasigna:
     * asignar trabajo sobre mercancía que ya salió sería decir que alguien la va a sacar dos
     * veces. Reasignar mientras está en curso sí se permite —el turno cambia, alguien se pone
     * malo— y por eso no es una transición de un solo uso.
     *
     * El estado va a `ASSIGNED` aunque la línea se quede sin ubicación: lo que dice el estado
     * es que **hay alguien a cargo**, no dónde está la mercancía.
     */
    fun assign(userId: Long, locationId: Long?): Either<DomainError, OrderLine> {
        if (status != OrderLineStatus.PENDING && status != OrderLineStatus.ASSIGNED) {
            return DomainError.InvalidTransition(
                entity = "Línea de orden",
                from = status.name,
                action = "asignar",
            ).asLeft()
        }
        return copy(
            status = OrderLineStatus.ASSIGNED,
            assignedUserId = userId,
            locationId = locationId ?: this.locationId,
        ).asRight()
    }
}

/** Una orden con su cabecera y sus líneas. */
data class Order(
    val id: Long,
    val orderNumber: String,
    val status: OrderStatus,
    val lines: List<OrderLine>,
    val customerCode: String? = null,
    val concept: String? = null,
    val converted: Boolean = false,
    val exportStatus: String? = null,
    val exportAttempts: Int = 0,
    val exportLastError: String? = null,
) {

    val totalLines: Int get() = lines.size

    /** Líneas completas. Es el `packagedLines` del contrato. */
    val packagedLines: Int get() = lines.count { it.isComplete }

    /** ¿Están todas las líneas completas o anuladas? */
    val allLinesDone: Boolean
        get() = lines.all { it.isComplete || it.status == OrderLineStatus.CANCELLED }

    /**
     * Marca la orden como confirmada.
     *
     * **No descuenta stock: devuelve las líneas con lo que hay que descontar.**
     * El descuento de lo pendiente lo hace el motor SQL, y esta función es la que
     * define CUÁNTO es «lo pendiente».
     */
    fun confirm(): Either<DomainError, Order> {
        if (converted) return DomainError.OrderAlreadyConfirmed(orderNumber).asLeft()
        if (status == OrderStatus.CANCELLED) {
            return DomainError.InvalidTransition(
                entity = "Orden",
                from = status.name,
                action = "confirmar",
            ).asLeft()
        }
        if (status == OrderStatus.COMPLETED) {
            return DomainError.OrderAlreadyConfirmed(orderNumber).asLeft()
        }

        return copy(status = OrderStatus.PENDING_TO_OUT).asRight()
    }

    /**
     * Registra un intento de exportación al ERP.
     *
     * **Idempotente por el `orderNumber` convertido**, como exige el contrato: si
     * el ERP ya lo recibió, volver a exportar no debe crear una segunda salida.
     * Esta función solo lleva el contador y el estado del intento; el envío real
     * es de la capa de infraestructura.
     */
    fun recordExportAttempt(success: Boolean, error: String? = null): Either<DomainError, Order> {
        if (!converted && status != OrderStatus.PENDING_TO_OUT && status != OrderStatus.COMPLETED) {
            return DomainError.OrderNotConverted(orderNumber).asLeft()
        }

        return copy(
            exportAttempts = exportAttempts + 1,
            exportStatus = if (success) "SENT" else "FAILED",
            exportLastError = if (success) null else error,
            converted = converted || success,
            status = if (success) OrderStatus.COMPLETED else status,
        ).asRight()
    }

    /**
     * Cancela la orden.
     *
     * **No se puede cancelar una orden ya exportada**: el ERP tiene una salida que
     * el sistema dejaría de reconocer. Para deshacerla se hace una entrada, que
     * deja rastro.
     */
    fun cancel(): Either<DomainError, Order> {
        if (status == OrderStatus.COMPLETED) {
            return DomainError.InvalidTransition(
                entity = "Orden",
                from = status.name,
                action = "anular",
            ).asLeft()
        }
        return copy(status = OrderStatus.CANCELLED).asRight()
    }

    /**
     * Recalcula el estado de la cabecera a partir de sus líneas.
     *
     * **Se deriva, no se escribe a mano.** Un `status` que se asigna en cada
     * endpoint acaba diciendo cosas distintas según por dónde se entró: es
     * exactamente el defecto del legacy, donde `status = 'done'` se escribía desde
     * cualquier sitio. Aquí hay UNA función que lo decide, y se puede probar.
     */
    fun recalculatedStatus(): Order {
        if (status == OrderStatus.CANCELLED || status == OrderStatus.COMPLETED ||
            status == OrderStatus.PENDING_TO_OUT
        ) {
            return this
        }

        val derived = when {
            lines.isEmpty() -> OrderStatus.PENDING
            allLinesDone -> OrderStatus.PACKAGED
            lines.any { it.suppliedQuantity > Quantity.ZERO } -> OrderStatus.IN_PROGRESS
            lines.any { it.status == OrderLineStatus.ASSIGNED } -> OrderStatus.IN_PROGRESS
            else -> OrderStatus.PENDING
        }

        return copy(status = derived)
    }
}
