package dev.axzy.stock.application.fulfillment

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.fulfillment.OrderLine
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.Instant
import java.time.LocalDate

/**
 * Los puertos de surtido (M04) y de la exportación al ERP (M09).
 *
 * La orden **no toca el inventario al crearse**: el stock sale al empacar y al
 * confirmar, que son actos con responsable. Toda escritura de existencias pasa por el
 * mismo motor que usa recepción (`registrar_movimiento`).
 */

data class OrderHeader(
    val id: Long,
    val orderNumber: String,
    val packagingDate: LocalDate?,
    val status: String,
    val customerCode: String?,
    val concept: String?,
    val converted: Boolean,
    val convertedAt: Instant?,
    val exportStatus: String?,
    val exportAttempts: Int,
    val exportLastError: String?,
    val totalLines: Int,
    val packagedLines: Int,
)

data class OrderLineView(
    val line: OrderLine,
    val unitMeasure: String,
    val assignedUserName: String?,
    val locationDisplayName: String?,
)

data class OrderDetail(val header: OrderHeader, val lines: List<OrderLineView>)

data class OrderFilter(val status: String?, val query: String?)

/** Una línea tal como llega en el alta. */
data class NewOrderLine(val productCode: String?, val quantity: Quantity?)

/** La orden tal como llega en el alta: el número y la fecha son opcionales. */
data class NewOrder(
    val orderNumber: String?,
    val packagingDate: String?,
    val customerCode: String?,
    val concept: String?,
    val lines: List<NewOrderLine>,
)

/** Una línea del alta con su producto ya resuelto. */
data class ResolvedOrderLine(
    val productId: Long,
    val productCode: ProductCode,
    val unitMeasureId: Long,
    val quantity: Quantity,
)

/** La cabecera ya validada, lista para escribir. */
data class OrderDraft(
    val orderNumber: String,
    val packagingDate: LocalDate?,
    val customerCode: String?,
    val concept: String?,
)

/** Lo que la orden necesita saber de un producto y de una ubicación. */
data class OrderProduct(val id: Long, val unitMeasureId: Long)

data class OrderLocation(val id: Long, val warehouseId: Long, val displayName: String)

/** El resultado del alta: un identificador, o que el número ya existía. */
sealed interface InsertOrderResult {
    data class Inserted(val id: Long) : InsertOrderResult

    data object DuplicateNumber : InsertOrderResult
}

interface Orders {
    fun page(filter: OrderFilter, request: PageRequest): Page<OrderHeader>

    fun detail(id: Long): OrderDetail?

    fun orderNumberOf(id: Long): String?

    fun numberExists(orderNumber: String): Boolean

    /** El número correlativo del año: `ORD-AAAA-NNNN`, como el legacy. */
    fun nextOrderNumber(year: Int): String

    fun insert(draft: OrderDraft, lines: List<ResolvedOrderLine>): InsertOrderResult

    fun lineOf(orderId: Long, lineId: Long): OrderLineView?

    fun replaceLine(line: OrderLine, locationId: Long?, at: Instant): Unit

    fun setStatus(orderId: Long, status: String, at: Instant): Unit

    /**
     * **Reclama la confirmación** en una sola sentencia condicional.
     *
     * Es lo que hace que confirmar dos veces a la vez no descuente dos veces (S3): el
     * `WHERE` exige que la orden siga en un estado previo a la confirmación, así que
     * solo una de las dos transacciones se lleva la fila. Devuelve cuántas cambió: si
     * es cero, otra ya la confirmó.
     */
    fun claimConfirmation(orderId: Long, at: Instant): Int

    fun recordExport(orderId: Long, converted: Boolean, status: String, error: String?, at: Instant): Unit
}

/** De dónde salen el producto y la ubicación de una línea de orden. */
interface OrderCatalog {
    fun product(code: ProductCode): OrderProduct?

    fun location(id: Long): OrderLocation?
}

/** El resultado de un intento de envío al ERP. */
sealed interface ErpResult {
    /** El ERP lo recibió. */
    data object Sent : ErpResult

    /**
     * El intento falló, con un motivo **legible y guardable**.
     *
     * El motivo se guarda en `export_last_error` y el reintento manual lo vuelve a
     * mostrar: un «falló» sin causa obliga a mirar los registros del servidor.
     */
    data class Failed(val reason: String) : ErpResult
}

/**
 * La conexión con el ERP.
 *
 * **Hoy no existe** ([ADR-0013], `docs/guia/lo-no-verificado.md`): la implementación
 * cableada devuelve `ERP_NOT_CONNECTED` y el contador de intentos lo refleja. El
 * puerto está aquí para que el día que haya ERP solo haya que escribir un adaptador, y
 * para que el caso de uso ya aplique las reglas que no dependen de él: la confirmación
 * no se revierte si el envío falla.
 */
interface ErpClient {
    fun send(order: OrderDetail): ErpResult
}
