package dev.axzy.stock.backend.infrastructure.outbound.persistence.receiving

import dev.axzy.stock.backend.application.audit.AuditEntry
import dev.axzy.stock.backend.application.receiving.CapturableLine
import dev.axzy.stock.backend.application.receiving.DiscrepancyLine
import dev.axzy.stock.backend.application.receiving.InvoiceDetail
import dev.axzy.stock.backend.application.receiving.InvoiceLineDto
import dev.axzy.stock.backend.application.receiving.InvoiceSummary
import dev.axzy.stock.backend.application.receiving.LineCapture
import dev.axzy.stock.backend.application.receiving.NewInvoiceLine
import dev.axzy.stock.backend.application.receiving.PendingApprovalDto
import dev.axzy.stock.backend.application.receiving.ReceivingRepository
import dev.axzy.stock.backend.application.receiving.ReceivingWork
import dev.axzy.stock.backend.infrastructure.outbound.persistence.audit.AuditWriter
import dev.axzy.stock.backend.infrastructure.outbound.persistence.shared.getLongOrNull
import dev.axzy.stock.backend.infrastructure.outbound.persistence.shared.setNullableLong
import dev.axzy.stock.backend.infrastructure.outbound.persistence.shared.toDecimal
import dev.axzy.stock.backend.infrastructure.outbound.persistence.shared.toQuantity
import dev.axzy.stock.backend.infrastructure.outbound.persistence.shared.transactional
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import java.sql.Connection
import java.sql.Date
import java.sql.PreparedStatement
import java.time.LocalDate
import javax.sql.DataSource

/** Persistencia de la recepción en PostgreSQL: facturas y sus líneas. */
class JdbcReceivingRepository(
    private val dataSource: DataSource,
    private val auditWriter: AuditWriter = AuditWriter(dataSource),
) : ReceivingRepository {

    override fun find(id: Long): InvoiceDetail? = dataSource.connection.use { connection ->
        val header = connection.prepareStatement(
            """
            SELECT i.id, i.invoice_number, i.receipt_date, i.invoice_type, i.status,
                   i.customer_code, i.concept, i.created_at
            FROM invoices i WHERE i.id = ? AND NOT i.deleted
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    InvoiceDetail(
                        id = rs.getLong("id"),
                        invoiceNumber = rs.getString("invoice_number"),
                        receiptDate = rs.getDate("receipt_date").toLocalDate().toString(),
                        invoiceType = rs.getString("invoice_type"),
                        status = rs.getString("status"),
                        customerCode = rs.getString("customer_code"),
                        concept = rs.getString("concept"),
                    )
                } else {
                    null
                }
            }
        } ?: return@use null

        val lines = readLines(connection, id)
        header.copy(
            totalLines = lines.size,
            pendingApprovalLines = lines.count { it.status == InvoiceLineStatus.DISCREPANCY.name },
            lines = lines,
        )
    }

    override fun list(status: String?, search: String?, offset: Int, limit: Int): List<InvoiceSummary> =
        dataSource.connection.use { connection ->
            val sql = buildString {
                append(SUMMARY_SELECT)
                append(" WHERE NOT i.deleted ")
                appendInvoiceFilter(status, search)
                append(" ORDER BY i.receipt_date DESC, i.id DESC LIMIT ? OFFSET ?")
            }
            connection.prepareStatement(sql).use { st ->
                var i = st.bindInvoiceFilter(status, search)
                st.setInt(i++, limit)
                st.setInt(i, offset)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toInvoiceSummary()) } }
            }
        }

    override fun count(status: String?, search: String?): Long = dataSource.connection.use { connection ->
        val sql = buildString {
            append("SELECT count(*) FROM invoices i WHERE NOT i.deleted ")
            appendInvoiceFilter(status, search)
        }
        connection.prepareStatement(sql).use { st ->
            st.bindInvoiceFilter(status, search)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    override fun pendingApprovals(): List<PendingApprovalDto> = dataSource.connection.use { connection ->
        connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT i.id, i.invoice_number, i.receipt_date, i.status,
                       count(l.id) AS pending_lines,
                       COALESCE(SUM(ABS(l.difference)), 0) AS total_difference
                FROM invoices i
                JOIN invoice_lines l ON l.invoice_id = i.id
                WHERE l.status = 'DISCREPANCY'::invoice_line_status
                  AND NOT l.deleted AND NOT i.deleted
                GROUP BY i.id, i.invoice_number, i.receipt_date, i.status
                ORDER BY i.receipt_date, i.id
                """.trimIndent(),
            ).use { rs -> buildList { while (rs.next()) add(rs.toPendingApprovalDto()) } }
        }
    }

    override fun <T> inTransaction(work: ReceivingWork.() -> T): T =
        dataSource.transactional { connection -> JdbcReceivingWork(connection, auditWriter).work() }

    private companion object {
        /**
         * La cabecera con sus contadores, para el listado. `pending_lines` cuenta las líneas que
         * **bloquean** el cierre, con el mismo criterio que aplica el cierre: la lista y la
         * acción no pueden discrepar.
         */
        const val SUMMARY_SELECT = """
            SELECT i.id, i.invoice_number, i.receipt_date, i.invoice_type, i.status,
                   i.customer_code, i.concept, i.created_at,
                   COALESCE(l.total_lines, 0) AS total_lines,
                   COALESCE(l.pending_lines, 0) AS pending_lines
            FROM invoices i
            LEFT JOIN (
                SELECT invoice_id,
                       count(*) AS total_lines,
                       count(*) FILTER (WHERE status IN ('PENDING', 'DISCREPANCY', 'ASSIGNED'))
                           AS pending_lines
                FROM invoice_lines WHERE NOT deleted GROUP BY invoice_id
            ) l ON l.invoice_id = i.id
        """
    }
}

// ── El filtro del listado, compartido con su conteo ───────────────────────────

private fun StringBuilder.appendInvoiceFilter(status: String?, search: String?) {
    if (status != null) append(" AND i.status = ?::invoice_status ")
    if (!search.isNullOrBlank()) {
        append(" AND (i.invoice_number ILIKE ? OR i.concept ILIKE ? OR i.customer_code ILIKE ?) ")
    }
}

/** Enlaza los parámetros del filtro y devuelve el índice del siguiente. */
private fun PreparedStatement.bindInvoiceFilter(status: String?, search: String?): Int {
    var i = 1
    if (status != null) setString(i++, status)
    if (!search.isNullOrBlank()) {
        val pattern = "%${search.trim()}%"
        repeat(3) { setString(i++, pattern) }
    }
    return i
}

private fun readLines(connection: Connection, invoiceId: Long): List<InvoiceLineDto> =
    connection.prepareStatement(
        """
        SELECT l.id, l.product_id, p.product_code, p.name AS product_name,
               l.container, l.quantity, l.box_quantity, l.units_per_box,
               l.physical_quantity, l.physical_box_quantity, l.located_quantity,
               l.difference, l.status, l.captured_by, uc.name AS captured_by_name,
               l.captured_at, l.approved_by, ua.name AS approved_by_name,
               l.approved_at, l.adjustment_reason,
               l.location_id, loc.hall, loc.code, loc."position", loc.kind
        FROM invoice_lines l
        JOIN products p ON p.id = l.product_id
        LEFT JOIN users uc ON uc.id = l.captured_by
        LEFT JOIN users ua ON ua.id = l.approved_by
        LEFT JOIN locations loc ON loc.id = l.location_id
        WHERE l.invoice_id = ? AND NOT l.deleted
        ORDER BY l.id
        """.trimIndent(),
    ).use { st ->
        st.setLong(1, invoiceId)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toInvoiceLineDto()) } }
    }

/**
 * Las operaciones de [ReceivingWork] sobre una conexión. Vive y muere con la transacción que la
 * crea: no guarda nada entre llamadas.
 */
private class JdbcReceivingWork(
    private val connection: Connection,
    private val auditWriter: AuditWriter,
) : ReceivingWork {

    override fun invoiceIdByNumber(number: String): Long? =
        connection.prepareStatement("SELECT id FROM invoices WHERE invoice_number = ?").use { st ->
            st.setString(1, number)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }

    override fun insertInvoice(number: String, date: LocalDate, type: String): Long =
        connection.prepareStatement(
            """
            INSERT INTO invoices (invoice_number, receipt_date, invoice_type, status, concept)
            VALUES (?, ?, ?::invoice_type, 'PENDING'::invoice_status, ?)
            RETURNING id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, number)
            st.setDate(2, Date.valueOf(date))
            st.setString(3, type)
            st.setString(4, null)
            st.executeQuery().use { rs -> rs.next(); rs.getLong("id") }
        }

    override fun activeProductId(productCode: String): Long? =
        connection.prepareStatement("SELECT id FROM products WHERE product_code = ? AND active").use { st ->
            st.setString(1, productCode)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }

    override fun insertLine(invoiceId: Long, productId: Long, line: NewInvoiceLine) {
        connection.prepareStatement(
            """
            INSERT INTO invoice_lines (
                invoice_id, product_id, container, unit_measure_id,
                quantity, box_quantity, units_per_box, status
            )
            SELECT ?, ?, ?, u.id, ?, ?, ?, 'PENDING'::invoice_line_status
            FROM units_of_measure u WHERE u.code = ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, invoiceId)
            st.setLong(2, productId)
            st.setString(3, line.container)
            st.setBigDecimal(4, line.quantity.toDecimal())
            st.setBigDecimal(5, line.boxQuantity.toDecimal())
            st.setBigDecimal(6, line.unitsPerBox?.toDecimal())
            st.setString(7, line.unitMeasure)
            st.executeUpdate()
        }
    }

    override fun lockStatus(invoiceId: Long): String? =
        connection.prepareStatement("SELECT status FROM invoices WHERE id = ? AND NOT deleted FOR UPDATE").use { st ->
            st.setLong(1, invoiceId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    override fun lineStatuses(invoiceId: Long): List<String> =
        connection.prepareStatement("SELECT status FROM invoice_lines WHERE invoice_id = ? AND NOT deleted").use { st ->
            st.setLong(1, invoiceId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    override fun setStatus(invoiceId: Long, status: String) {
        connection.prepareStatement(
            "UPDATE invoices SET status = ?::invoice_status, updated_at = now() WHERE id = ?",
        ).use { st ->
            st.setString(1, status)
            st.setLong(2, invoiceId)
            st.executeUpdate()
        }
    }

    override fun markClosed(invoiceId: Long, userId: Long?) {
        connection.prepareStatement(
            "UPDATE invoices SET status = 'COMPLETED', reviewed_by = ?, reviewed_at = now(), " +
                "updated_at = now() WHERE id = ?",
        ).use { st ->
            st.setNullableLong(1, userId)
            st.setLong(2, invoiceId)
            st.executeUpdate()
        }
    }

    override fun line(invoiceId: Long, lineId: Long): CapturableLine? =
        connection.prepareStatement(
            "SELECT id, quantity, status FROM invoice_lines WHERE id = ? AND invoice_id = ? AND NOT deleted",
        ).use { st ->
            st.setLong(1, lineId)
            st.setLong(2, invoiceId)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    CapturableLine(
                        id = rs.getLong("id"),
                        invoiced = rs.getBigDecimal("quantity").toQuantity(),
                        status = rs.getString("status"),
                    )
                } else {
                    null
                }
            }
        }

    override fun saveCapture(invoiceId: Long, lineId: Long, capture: LineCapture) {
        connection.prepareStatement(
            """
            UPDATE invoice_lines
            SET physical_quantity = ?, physical_box_quantity = ?,
                status = ?::invoice_line_status,
                captured_by = ?, captured_at = now(), updated_at = now()
            WHERE id = ? AND invoice_id = ?
            """.trimIndent(),
        ).use { st ->
            st.setBigDecimal(1, capture.physical.toDecimal())
            st.setBigDecimal(2, capture.physicalBoxes?.toDecimal())
            st.setString(3, capture.status.name)
            st.setLong(4, capture.userId)
            st.setLong(5, lineId)
            st.setLong(6, invoiceId)
            st.executeUpdate()
        }
    }

    override fun lines(invoiceId: Long): List<InvoiceLineDto> = readLines(connection, invoiceId)

    override fun discrepancies(invoiceId: Long): List<DiscrepancyLine> =
        connection.prepareStatement(
            """
            SELECT id, captured_by FROM invoice_lines
            WHERE invoice_id = ? AND status = 'DISCREPANCY'::invoice_line_status AND NOT deleted
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, invoiceId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(DiscrepancyLine(rs.getLong("id"), rs.getLongOrNull("captured_by"))) }
            }
        }

    /**
     * `approved_by` guarda a quien **resolvió** la discrepancia, la aprobara o la rechazara: es
     * la misma firma en las dos salidas.
     */
    override fun resolveDiscrepancies(invoiceId: Long, outcome: InvoiceLineStatus, reason: String, userId: Long) {
        connection.prepareStatement(
            """
            UPDATE invoice_lines
            SET status = ?::invoice_line_status,
                approved_by = ?, approved_at = now(),
                adjustment_reason = ?::adjustment_reason,
                updated_at = now()
            WHERE invoice_id = ?
              AND status = 'DISCREPANCY'::invoice_line_status
              AND NOT deleted
            """.trimIndent(),
        ).use { st ->
            st.setString(1, outcome.name)
            st.setLong(2, userId)
            st.setString(3, reason)
            st.setLong(4, invoiceId)
            st.executeUpdate()
        }
    }

    override fun audit(entry: AuditEntry): Long = auditWriter.record(connection, entry)
}
