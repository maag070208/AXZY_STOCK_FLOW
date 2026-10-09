package dev.axzy.stock.backend.application.receiving

import dev.axzy.stock.backend.application.audit.AuditEntry
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.Quantity
import java.time.LocalDate

/**
 * Lo que la recepción necesita de la persistencia.
 *
 * Las consultas se piden sueltas. Lo que cambia una factura o sus líneas se pide dentro de
 * [inTransaction]: todo lo que haga el bloque se confirma o se revierte junto.
 */
interface ReceivingRepository {

    /** La factura con sus líneas y sus contadores, o `null` si no existe o está borrada. */
    fun find(id: Long): InvoiceDetail?

    fun list(status: String?, search: String?, offset: Int, limit: Int): List<InvoiceSummary>

    fun count(status: String?, search: String?): Long

    /** Las facturas con líneas en discrepancia, de la más antigua a la más reciente. */
    fun pendingApprovals(): List<PendingApprovalDto>

    fun <T> inTransaction(work: ReceivingWork.() -> T): T
}

/** Las operaciones disponibles dentro de una transacción de recepción. */
interface ReceivingWork {

    fun invoiceIdByNumber(number: String): Long?

    fun insertInvoice(number: String, date: LocalDate, type: String): Long

    fun activeProductId(productCode: String): Long?

    fun insertLine(invoiceId: Long, productId: Long, line: NewInvoiceLine)

    /**
     * El estado de la factura, **bloqueándola** hasta el final de la transacción: dos cierres
     * simultáneos no pueden leer ambos «abierta». `null` si no existe o está borrada.
     */
    fun lockStatus(invoiceId: Long): String?

    /** Los estados de las líneas vivas, tal como los guarda la base. */
    fun lineStatuses(invoiceId: Long): List<String>

    fun setStatus(invoiceId: Long, status: String)

    fun markClosed(invoiceId: Long, userId: Long?)

    /** Lo mínimo de una línea para decidir su captura, o `null` si no es de esa factura. */
    fun line(invoiceId: Long, lineId: Long): CapturableLine?

    fun saveCapture(invoiceId: Long, lineId: Long, capture: LineCapture)

    /** Las líneas de la factura con todos sus campos, incluida la `difference` que calcula la base. */
    fun lines(invoiceId: Long): List<InvoiceLineDto>

    /** Las líneas en discrepancia y quién contó cada una. */
    fun discrepancies(invoiceId: Long): List<DiscrepancyLine>

    /**
     * Resuelve TODAS las líneas en discrepancia de la factura con el mismo desenlace: las deja
     * en [outcome] y anota quién lo decidió y por qué.
     */
    fun resolveDiscrepancies(invoiceId: Long, outcome: InvoiceLineStatus, reason: String, userId: Long)

    fun audit(entry: AuditEntry): Long
}

/** Una línea nueva, tal como llega para crear la factura. */
data class NewInvoiceLine(
    val productCode: String,
    val container: String?,
    val unitMeasure: String,
    val quantity: Quantity,
    val boxQuantity: Quantity,
    val unitsPerBox: Quantity? = null,
)

data class CapturableLine(val id: Long, val invoiced: Quantity, val status: String)

data class LineCapture(
    val physical: Quantity,
    val physicalBoxes: Quantity?,
    val status: InvoiceLineStatus,
    val userId: Long,
)

data class DiscrepancyLine(val id: Long, val capturedBy: Long?)
