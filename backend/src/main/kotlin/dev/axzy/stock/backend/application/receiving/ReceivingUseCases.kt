package dev.axzy.stock.backend.application.receiving

import dev.axzy.stock.backend.application.audit.AuditChannel
import dev.axzy.stock.backend.application.audit.AuditEntry
import dev.axzy.stock.backend.application.shared.unitsText
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.Quantity
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Casos de uso de recepción (contrato §5), con **la regla B1** en el centro.
 *
 * La recepción es el flujo que el legacy tenía peor: una línea con discrepancia se podía ubicar
 * sin que nadie la aprobara, así que la diferencia entre lo facturado y lo contado se perdía en
 * el inventario. Aquí la diferencia la resuelve un supervisor, y la base lo respalda con un
 * `CHECK`: es imposible una línea `APPROVED` sin aprobador ni motivo.
 *
 * **La aprobación es por FACTURA, no por línea** (decisión R-86). Aprobar línea a línea obliga a
 * repetir el mismo juicio N veces e invita a aprobar lo propio sin darse cuenta.
 */
class ReceivingUseCases(private val repository: ReceivingRepository) {

    /**
     * Crea una factura con sus líneas, en UNA transacción: una factura a medio crear es un
     * documento que no sirve y que bloquea el flujo de recepción.
     */
    fun createInvoice(number: String, date: LocalDate, type: String, lines: List<NewInvoiceLine>): Long =
        repository.inTransaction {
            invoiceIdByNumber(number)?.let { existing ->
                throw ConflictException(
                    "DUPLICATE_INVOICE_NUMBER",
                    "Ya existe una factura con el número '$number'.",
                    mapOf("invoiceId" to existing.toString()),
                )
            }

            val invoiceId = insertInvoice(number, date, type)

            for (line in lines) {
                val productId = activeProductId(line.productCode)
                    ?: throw UnprocessableException(
                        "PRODUCT_NOT_FOUND",
                        "El producto '${line.productCode}' no existe en el catálogo.",
                    )
                insertLine(invoiceId, productId, line)
            }

            invoiceId
        }

    fun invoice(id: Long): InvoiceDetail = repository.find(id) ?: throw notFound(id)

    /** Solo la cabecera y sus contadores. Quien necesita las líneas pide el detalle. */
    fun list(status: String?, search: String?, offset: Int, limit: Int): List<InvoiceSummary> =
        repository.list(status, search, offset, limit)

    fun count(status: String?, search: String?): Long = repository.count(status, search)

    /** Bandeja de pendientes de aprobación. **Pantalla nueva: el legacy no la tenía.** */
    fun pendingApprovals(): List<PendingApprovalDto> = repository.pendingApprovals()

    /**
     * Cierra la factura.
     *
     * **No se cierra con líneas que bloquean**, y cuáles bloquean lo dice el dominio
     * ([InvoiceLineStatus.blocksInvoiceClosing]): sin capturar, con una diferencia sin aprobar o
     * con trabajo a medias. El cierre es lo que declara el trabajo terminado; a partir de ahí
     * nadie vuelve a mirarla.
     */
    fun close(invoiceId: Long, userId: Long?): InvoiceDetail {
        repository.inTransaction {
            val current = lockStatus(invoiceId) ?: throw notFound(invoiceId)

            // Cerrar dos veces no es un fallo del cliente: es que ya está cerrada. Un `409` lo
            // dice mejor que un `200` que haría creer que este cierre hizo algo.
            if (current == "COMPLETED") {
                throw ConflictException(
                    code = "INVOICE_ALREADY_CLOSED",
                    message = "La factura $invoiceId ya está cerrada.",
                )
            }
            if (current == "CANCELLED") {
                throw ConflictException(
                    code = "INVALID_TRANSITION",
                    message = "Una factura anulada no se puede cerrar.",
                    details = mapOf("status" to current),
                )
            }

            // Un estado que el dominio no conoce bloquea: ante la duda, no se cierra.
            val blocking = lineStatuses(invoiceId).filter { name ->
                runCatching { InvoiceLineStatus.valueOf(name) }.getOrNull()?.blocksInvoiceClosing ?: true
            }
            if (blocking.isNotEmpty()) {
                throw UnprocessableException(
                    code = "INVOICE_HAS_PENDING_LINES",
                    message = "No se puede cerrar: hay ${blocking.size} línea(s) que bloquean el " +
                        "cierre. Una línea sin capturar, con una diferencia sin aprobar o con trabajo " +
                        "asignado a medias impide declarar la recepción terminada.",
                    details = mapOf(
                        "pendingLines" to blocking.size.toString(),
                        "statuses" to blocking.distinct().joinToString(", "),
                    ),
                )
            }

            markClosed(invoiceId, userId)
        }
        return invoice(invoiceId)
    }

    /**
     * Registra el conteo físico de una línea. La `difference` la calcula **la base**, con su
     * columna generada; aquí solo se decide el estado: coincide o hay discrepancia.
     *
     * @return la línea completa, con la `difference` ya calculada: el cliente la necesita para
     *   saber si tiene que aprobar algo.
     */
    fun captureLine(
        invoiceId: Long,
        lineId: Long,
        quantityPhysical: Quantity,
        boxesPhysical: Quantity?,
        userId: Long,
    ): InvoiceLineDto = repository.inTransaction {
        val line = line(invoiceId, lineId)
            ?: throw NotFoundException("La línea $lineId no existe en la factura $invoiceId.", "LINE_NOT_FOUND")

        if (line.status == InvoiceLineStatus.APPROVED.name || line.status == InvoiceLineStatus.LOCATED.name) {
            throw ConflictException(
                "LINE_ALREADY_RESOLVED",
                "La línea ya está ${line.status.lowercase()} y no se puede volver a capturar.",
            )
        }

        val newStatus =
            if (quantityPhysical == line.invoiced) InvoiceLineStatus.CAPTURED else InvoiceLineStatus.DISCREPANCY

        saveCapture(invoiceId, lineId, LineCapture(quantityPhysical, boxesPhysical, newStatus, userId))
        refreshInvoiceStatus(invoiceId)

        // El conteo es el dato del que depende toda la discrepancia. Lleva las CANTIDADES y no
        // solo el estado: «contó 90 donde decía 100» es lo que permite investigar.
        audit(
            AuditEntry(
                actorUserId = userId,
                channel = AuditChannel.WEB,
                entityType = "invoice_line",
                entityId = lineId.toString(),
                entityLabel = "invoice #$invoiceId",
                action = "invoice.line.capture",
                previousState = """{"status":"PENDING","invoiced":"${line.invoiced.unitsText()}"}""",
                newState = """{"status":"${newStatus.name}","physical":"${quantityPhysical.unitsText()}"}""",
                permissionKey = "invoice.capture",
                businessDate = today(),
            ),
        )

        lines(invoiceId).first { it.id == lineId }
    }

    /**
     * Aprueba TODAS las líneas en discrepancia de una factura: la diferencia se **acepta**, la
     * mercancía se queda y el motivo explica por qué falta o sobra.
     *
     * Es el control interno central (decisión R-74). Su registro tiene que responder «¿quién
     * aceptó esta diferencia, cuándo y por qué?» dentro de un año, cuando ya no se pueda
     * reconstruir de otra forma.
     */
    fun approveInvoice(invoiceId: Long, reason: String, note: String?, approverId: Long): InvoiceDetail =
        resolveDiscrepancies(
            invoiceId = invoiceId,
            reason = reason,
            note = note,
            userId = approverId,
            resolution = Resolution.APPROVE,
        )

    /**
     * Rechaza TODAS las líneas en discrepancia: la diferencia **no se acepta** y las líneas se
     * cancelan; esa mercancía no entra al inventario.
     *
     * Sin esta salida, la única opción ante una discrepancia era aceptarla, y eso convierte el
     * control en un trámite: un supervisor que no puede rechazar acaba aprobando para
     * desbloquear la factura. **El rechazo es lo que da sentido a la aprobación.**
     */
    fun rejectDiscrepancy(invoiceId: Long, reason: String, note: String?, rejectedBy: Long): InvoiceDetail =
        resolveDiscrepancies(
            invoiceId = invoiceId,
            reason = reason,
            note = note,
            userId = rejectedBy,
            resolution = Resolution.REJECT,
        )

    // ── Piezas ────────────────────────────────────────────────────────────────

    /**
     * Aprobar y rechazar son la misma decisión tomada al revés, y por eso comparten las tres
     * comprobaciones. La segregación de funciones (R-74) vale para las DOS salidas: si solo
     * aplicara a aprobar, rechazar sería la puerta de atrás para deshacerse de una discrepancia
     * que uno mismo creó.
     */
    private fun resolveDiscrepancies(
        invoiceId: Long,
        reason: String,
        note: String?,
        userId: Long,
        resolution: Resolution,
    ): InvoiceDetail {
        repository.inTransaction {
            val pending = discrepancies(invoiceId)

            if (pending.isEmpty()) throw ConflictException("NO_PENDING_APPROVAL", resolution.nothingPending)

            // El error dice CUÁLES líneas, para que el supervisor sepa qué reasignar.
            val own = pending.filter { it.capturedBy == userId }.map { it.id }
            if (own.isNotEmpty()) {
                throw UnprocessableException(
                    "SEGREGATION_OF_DUTIES",
                    resolution.ownLines(own.joinToString(", "), userId),
                    mapOf("lines" to own.joinToString(",")),
                )
            }

            if (reason.isBlank()) throw UnprocessableException("REASON_REQUIRED", resolution.reasonRequired)

            resolveDiscrepancies(invoiceId, resolution.outcome, reason.uppercase(), userId)
            refreshInvoiceStatus(invoiceId)

            // El `newState` lleva el MOTIVO además del estado: la bitácora tiene que bastarse
            // sola, sin cruzar con una tabla de líneas que puede cambiar.
            audit(
                AuditEntry(
                    actorUserId = userId,
                    channel = AuditChannel.WEB,
                    entityType = "invoice",
                    entityId = invoiceId.toString(),
                    action = resolution.action,
                    previousState = """{"status":"DISCREPANCY","pendingLines":${pending.size}}""",
                    newState = """{"status":"${resolution.outcome.name}","${resolution.counter}":${pending.size},""" +
                        """"adjustmentReason":"$reason"}""",
                    reason = reason,
                    note = note,
                    permissionKey = resolution.permission,
                    businessDate = today(),
                ),
            )
        }
        return invoice(invoiceId)
    }

    /**
     * Deriva el estado de la factura del de sus líneas. **No se mantiene a mano**: el legacy
     * guardaba los dos por separado y se desincronizaban.
     */
    private fun ReceivingWork.refreshInvoiceStatus(invoiceId: Long) {
        val statuses = lineStatuses(invoiceId)
        val derived = when {
            statuses.isEmpty() -> "PENDING"
            statuses.any { it == "DISCREPANCY" } -> "PENDING_TO_LOCATE"
            statuses.all { it == "LOCATED" } -> "COMPLETED"
            statuses.all { it == "CANCELLED" } -> "CANCELLED"
            else -> "IN_PROGRESS"
        }
        setStatus(invoiceId, derived)
    }

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun notFound(id: Long) = NotFoundException("La factura $id no existe.", "INVOICE_NOT_FOUND")
}

/** Las dos salidas de una discrepancia, con lo que cambia entre una y otra. */
private enum class Resolution(
    val outcome: InvoiceLineStatus,
    val action: String,
    val permission: String,
    /** El nombre del contador de líneas en el `newState` de la bitácora. */
    val counter: String,
    val nothingPending: String,
    val reasonRequired: String,
    val ownLines: (lines: String, userId: Long) -> String,
) {
    APPROVE(
        outcome = InvoiceLineStatus.APPROVED,
        action = "invoice.discrepancy.approve",
        permission = "invoice.approve",
        counter = "approvedLines",
        nothingPending = "La factura no tiene líneas en discrepancia que aprobar. " +
            "Solo se aprueban las líneas con diferencia respecto a lo facturado.",
        reasonRequired = "Aprobar una discrepancia exige un motivo: dentro de un año nadie " +
            "podrá explicar por qué se aceptó la diferencia.",
        ownLines = { lines, userId ->
            "No puedes aprobar una discrepancia que tú mismo capturaste. " +
                "Las líneas $lines las contó el usuario $userId, así que tiene que aprobarlas otra persona."
        },
    ),
    REJECT(
        outcome = InvoiceLineStatus.CANCELLED,
        action = "invoice.discrepancy.reject",
        permission = "invoice.reject",
        counter = "cancelledLines",
        nothingPending = "La factura no tiene líneas en discrepancia que rechazar. " +
            "Solo se rechazan las líneas con diferencia respecto a lo facturado.",
        reasonRequired = "Rechazar una discrepancia exige un motivo: dentro de un año nadie " +
            "podrá explicar por qué se canceló la línea.",
        ownLines = { lines, userId ->
            "No puedes rechazar una discrepancia que tú mismo capturaste. " +
                "Las líneas $lines las contó el usuario $userId, así que tiene que resolverlas otra persona."
        },
    ),
}
