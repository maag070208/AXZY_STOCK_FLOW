package dev.axzy.stock.application.receiving

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import java.time.Instant
import java.time.LocalDate

/**
 * Los puertos de recepción (M03, docs/flujos/recepcion.md).
 *
 * La factura es un documento con líneas, y las dos cosas se leen juntas: el listado
 * devuelve **solo la cabecera** con sus contadores —que la base calcula— y el detalle
 * añade las líneas. Separarlos es lo que permite que un listado de 200 facturas no
 * traiga 2.400 líneas que nadie va a mirar.
 */

/** La cabecera de una factura, con los contadores que la base calcula. */
data class InvoiceHeader(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: LocalDate,
    val invoiceType: String,
    val status: String,
    val customerCode: String?,
    val concept: String?,
    val totalLines: Int,
    /**
     * Las líneas que **impiden cerrar**: sin capturar, en discrepancia o asignadas a
     * medias. Es el mismo criterio que aplica el cierre, calculado en la base, así que
     * la lista y la acción no pueden discrepar.
     */
    val pendingApprovalLines: Int,
    val createdAt: Instant,
)

/** Una línea con lo que la respuesta necesita y la línea del dominio no tiene: nombres. */
data class InvoiceLineView(
    val line: InvoiceLine,
    val productId: Long,
    val productName: String,
    val container: String?,
    val boxQuantity: Quantity,
    val difference: Quantity?,
    val capturedByName: String?,
    val capturedAt: Instant?,
    val approvedByName: String?,
    val approvedAt: Instant?,
    val locationDisplayName: String?,
)

data class InvoiceDetail(val header: InvoiceHeader, val lines: List<InvoiceLineView>)

/** Filtros del listado de facturas. */
data class InvoiceFilter(val status: String?, val query: String?)

/** Una línea tal como llega en el alta. */
data class NewInvoiceLine(
    val productCode: String?,
    val container: String?,
    val unitMeasure: String?,
    val quantity: Quantity?,
    val boxQuantity: Quantity?,
    val unitsPerBox: Quantity?,
)

/**
 * La factura tal como llega en el alta: sin validar.
 *
 * Los campos son opcionales a propósito, para que el caso de uso diga **cuál** falta
 * en lugar de que el cliente reciba un error de deserialización sin campo.
 */
data class NewInvoice(
    val invoiceNumber: String?,
    val receiptDate: String?,
    val invoiceType: String?,
    val lines: List<NewInvoiceLine>,
)

/** La cabecera ya validada, lista para escribir. */
data class InvoiceDraft(
    val invoiceNumber: String,
    val receiptDate: LocalDate,
    val invoiceType: String,
)

/**
 * Una línea del alta con sus identificadores ya resueltos.
 *
 * El producto y la unidad se resuelven antes de escribir: la clave de negocio
 * (`productCode`) es lo que manda el cliente, y el `id` es lo que guarda la tabla.
 */
data class ResolvedLine(
    val productId: Long,
    val productCode: ProductCode,
    val unitMeasureId: Long,
    val container: String?,
    val quantity: Quantity,
    val boxQuantity: Quantity,
    /** Unidades por caja: es un entero positivo, y el tipo del dominio lo garantiza. */
    val unitsPerBox: UnitsPerBox,
)

/** Una factura con líneas que esperan decisión, para la bandeja. */
data class PendingApproval(
    val invoiceId: Long,
    val invoiceNumber: String,
    val receiptDate: LocalDate,
    val status: String,
    val pendingLines: Int,
    /** Suma de las diferencias **absolutas**: va positiva aunque falte mercancía. */
    val totalDifference: Quantity,
)

interface Invoices {
    fun page(filter: InvoiceFilter, request: PageRequest): Page<InvoiceHeader>

    fun detail(id: Long): InvoiceDetail?

    /** La clave de negocio es única; el prechequeo da el error legible en el caso normal. */
    fun numberExists(invoiceNumber: String): Boolean

    fun insert(draft: InvoiceDraft, lines: List<ResolvedLine>): InsertResult

    /** Las líneas de una factura, sin los nombres: es lo que necesitan las transiciones. */
    fun linesOf(invoiceId: Long): List<InvoiceLine>

    fun lineOf(invoiceId: Long, lineId: Long): InvoiceLine?

    /** Escribe una línea: captura, aprobación o rechazo ya decididos por el dominio. */
    fun replaceLine(line: InvoiceLine, at: Instant): Unit

    fun setStatus(invoiceId: Long, status: String, at: Instant): Unit

    /** Cierra la factura: además del estado, deja quién la revisó y cuándo. */
    fun close(invoiceId: Long, actorId: Long, at: Instant): Unit

    /**
     * Aprueba **todas** las líneas en discrepancia de una factura, en una sentencia.
     *
     * La condición viaja en el `WHERE` (`status = 'DISCREPANCY'`) y el número de filas
     * afectadas se compara con el que se esperaba: si no coincide, alguien las cambió
     * a la vez y la operación no se da por buena.
     */
    fun approveDiscrepancies(invoiceId: Long, approverId: Long, reason: String, at: Instant): Int

    /**
     * Devuelve a captura todas las líneas en discrepancia de una factura, en una
     * sentencia: se limpian la cantidad contada, las cajas y quién capturó, para
     * forzar un reconteo. Es lo que hace [dev.axzy.stock.domain.receiving.InvoiceLine.rejectDiscrepancy].
     */
    fun rejectDiscrepancies(invoiceId: Long, at: Instant): Int

    fun pendingApproval(): List<PendingApproval>
}

/** Lo que la factura necesita saber de un producto del catálogo. */
data class ReceivingProduct(val id: Long, val unitMeasureId: Long)

/** De dónde salen los identificadores de un producto y de una unidad. */
interface ReceivingCatalog {
    fun product(code: ProductCode): ReceivingProduct?

    fun unitMeasureId(code: String): Long?
}

/** El resultado del alta: un identificador, o que el número ya existía. */
sealed interface InsertResult {
    data class Inserted(val id: Long) : InsertResult

    /**
     * El número de factura es único y ya estaba tomado.
     *
     * Es un resultado y no una excepción porque el prechequeo no basta: entre la
     * comprobación y el `INSERT` cabe otra petición, y el índice único es el que
     * decide. Devolverlo como valor evita que una carrera acabe en un `500`.
     */
    data object DuplicateNumber : InsertResult
}
