package dev.axzy.stock.adapters.persistence.receiving

import dev.axzy.stock.adapters.persistence.shared.toQuantity
import dev.axzy.stock.application.receiving.InvoiceDetail
import dev.axzy.stock.application.receiving.InvoiceDraft
import dev.axzy.stock.application.receiving.InvoiceFilter
import dev.axzy.stock.application.receiving.InvoiceHeader
import dev.axzy.stock.application.receiving.InvoiceLineView
import dev.axzy.stock.application.receiving.Invoices
import dev.axzy.stock.application.inventory.LocatingLines
import dev.axzy.stock.application.receiving.InsertResult
import dev.axzy.stock.application.receiving.PendingApproval
import dev.axzy.stock.application.receiving.ReceivingCatalog
import dev.axzy.stock.application.receiving.ReceivingProduct
import dev.axzy.stock.application.receiving.ResolvedLine
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The receiving invoices on PostgreSQL (docs/api/recepcion.md).
 *
 * Two decisions that are repeated throughout the file:
 *
 * · **The counters are computed in the database**, next to the header, in the same
 *   statement: requesting them per row would be one query per invoice in a listing of
 *   doscientas.
 * · **The rule for which line blocks comes from the domain**, not from a list written
 *   here. `BLOQUEANTES` is composed from `InvoiceLineStatus.blocksInvoiceClosing`, so
 *   that the list and the close action cannot be separated.
 */
class JdbcInvoices(private val jdbc: JdbcClient) : Invoices, ReceivingCatalog, LocatingLines {

    override fun page(filter: InvoiceFilter, request: PageRequest): Page<InvoiceHeader> {
        val where = mutableListOf("i.deleted = FALSE")
        val params = mutableMapOf<String, Any?>()

        filter.status?.let {
            where += "i.status = CAST(:status AS invoice_status)"
            params["status"] = it
        }
        filter.query?.let {
            where += "(i.invoice_number ILIKE :q OR i.customer_code ILIKE :q OR i.concept ILIKE :q)"
            params["q"] = "%${it.trim()}%"
        }
        val clause = "WHERE " + where.joinToString(" AND ")

        val total = query("SELECT count(*) FROM invoices i $clause", params) { rs, _ -> rs.getLong(1) }.single()
        val items = query(
            "$HEADER_COLUMNS $HEADER_FROM $clause ORDER BY i.receipt_date DESC, i.id DESC LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toHeader() }

        return Page(items, request.page, request.size, total)
    }

    override fun detail(id: Long): InvoiceDetail? {
        val header = query(
            "$HEADER_COLUMNS $HEADER_FROM WHERE i.deleted = FALSE AND i.id = :id",
            mapOf("id" to id),
        ) { rs, _ -> rs.toHeader() }.firstOrNull() ?: return null

        val lines = query(
            "$LINE_COLUMNS $LINE_FROM WHERE l.deleted = FALSE AND l.invoice_id = :id ORDER BY l.id",
            mapOf("id" to id),
        ) { rs, _ -> rs.toLineView() }

        return InvoiceDetail(header, lines)
    }

    override fun numberExists(invoiceNumber: String): Boolean =
        query(
            "SELECT 1 FROM invoices WHERE invoice_number = :number AND deleted = FALSE",
            mapOf("number" to invoiceNumber),
        ) { _, _ -> 1 }.isNotEmpty()

    override fun insert(draft: InvoiceDraft, lines: List<ResolvedLine>): InsertResult {
        val invoiceId = try {
            query(
                """
                INSERT INTO invoices (invoice_number, receipt_date, invoice_type, status)
                VALUES (:number, :date::date, CAST(:type AS invoice_type), 'PENDING')
                RETURNING id
                """.trimIndent(),
                mapOf(
                    "number" to draft.invoiceNumber,
                    "date" to draft.receiptDate.toString(),
                    "type" to draft.invoiceType,
                ),
            ) { rs, _ -> rs.getLong(1) }.single()
        } catch (error: DataIntegrityViolationException) {
            // The unique index decides in a race; the normal case is caught by the
            // use-case pre-check, but another request can fit in between the two.
            if (error.message?.contains(INVOICE_NUMBER_INDEX) == true) return InsertResult.DuplicateNumber
            throw error
        }

        // **One single statement for all the lines**: twelve lines cannot be twelve
        // round trips to the database (R-P9).
        val values = lines.indices.joinToString(", ") { index ->
            "(:invoice, :product$index, :container$index, :unit$index, :quantity$index, " +
                ":boxes$index, :unitsPerBox$index)"
        }
        val params = mutableMapOf<String, Any?>("invoice" to invoiceId)
        lines.forEachIndexed { index, line ->
            params["product$index"] = line.productId
            params["container$index"] = line.container
            params["unit$index"] = line.unitMeasureId
            params["quantity$index"] = line.quantity.toSql()
            params["boxes$index"] = line.boxQuantity.toSql()
            params["unitsPerBox$index"] = line.unitsPerBox.value.toBigDecimal()
        }
        update(
            """
            INSERT INTO invoice_lines (invoice_id, product_id, container, unit_measure_id,
                                       quantity, box_quantity, units_per_box)
            VALUES $values
            """.trimIndent(),
            params,
        )

        return InsertResult.Inserted(invoiceId)
    }

    override fun linesOf(invoiceId: Long): List<InvoiceLine> =
        query(
            "$LINE_COLUMNS $LINE_FROM WHERE l.deleted = FALSE AND l.invoice_id = :id ORDER BY l.id",
            mapOf("id" to invoiceId),
        ) { rs, _ -> rs.toLineView().line }

    override fun lineOf(invoiceId: Long, lineId: Long): InvoiceLine? =
        query(
            "$LINE_COLUMNS $LINE_FROM WHERE l.deleted = FALSE AND l.invoice_id = :invoice AND l.id = :line",
            mapOf("invoice" to invoiceId, "line" to lineId),
        ) { rs, _ -> rs.toLineView().line }.firstOrNull()

    override fun replaceLine(line: InvoiceLine, at: Instant) {
        update(
            """
            UPDATE invoice_lines SET
                physical_quantity = CAST(:physical AS numeric),
                physical_box_quantity = CAST(:physicalBoxes AS numeric),
                located_quantity = CAST(:located AS numeric),
                received_quantity = CAST(:received AS numeric),
                status = CAST(:status AS invoice_line_status),
                captured_by = CAST(:capturedBy AS bigint),
                captured_at = CASE WHEN CAST(:capturedBy AS bigint) IS NULL THEN NULL
                                   ELSE CAST(:at AS timestamptz) END,
                approved_by = CAST(:approvedBy AS bigint),
                approved_at = CASE WHEN CAST(:approvedBy AS bigint) IS NULL THEN NULL
                                   ELSE CAST(:at AS timestamptz) END,
                adjustment_reason = CAST(CAST(:reason AS text) AS adjustment_reason),
                updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint)
            """.trimIndent(),
            mapOf(
                "physical" to line.physicalQuantity?.toSql(),
                "physicalBoxes" to line.physicalBoxQuantity?.value?.toBigDecimal(),
                "located" to line.locatedQuantity.toSql(),
                "received" to line.receivedQuantity.toSql(),
                "status" to line.status.name,
                "capturedBy" to line.capturedBy,
                "approvedBy" to line.approvedBy,
                "reason" to line.adjustmentReason,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "id" to line.id,
            ),
        )
    }

    /**
     * The count, claimed against the balance entered that was read.
     *
     * It is the same `UPDATE` as [replaceLine] with one more condition: `received_quantity` has to
     * still be the one that was read. Without it, two simultaneous counts of the same line
     * would enter the same difference twice.
     */
    override fun claimCapture(line: InvoiceLine, expectedReceived: Quantity, at: Instant): Int =
        update(
            """
            UPDATE invoice_lines SET
                physical_quantity = CAST(:physical AS numeric),
                physical_box_quantity = CAST(:physicalBoxes AS numeric),
                located_quantity = CAST(:located AS numeric),
                received_quantity = CAST(:received AS numeric),
                status = CAST(:status AS invoice_line_status),
                captured_by = CAST(:capturedBy AS bigint),
                captured_at = CASE WHEN CAST(:capturedBy AS bigint) IS NULL THEN NULL
                                   ELSE CAST(:at AS timestamptz) END,
                approved_by = CAST(:approvedBy AS bigint),
                approved_at = CASE WHEN CAST(:approvedBy AS bigint) IS NULL THEN NULL
                                   ELSE CAST(:at AS timestamptz) END,
                adjustment_reason = CAST(CAST(:reason AS text) AS adjustment_reason),
                updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint)
              AND received_quantity = CAST(:expected AS numeric)
            """.trimIndent(),
            mapOf(
                "physical" to line.physicalQuantity?.toSql(),
                "physicalBoxes" to line.physicalBoxQuantity?.value?.toBigDecimal(),
                "located" to line.locatedQuantity.toSql(),
                "received" to line.receivedQuantity.toSql(),
                "expected" to expectedReceived.toSql(),
                "status" to line.status.name,
                "capturedBy" to line.capturedBy,
                "approvedBy" to line.approvedBy,
                "reason" to line.adjustmentReason,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "id" to line.id,
            ),
        )

    override fun assignLine(lineId: Long, userId: Long, at: Instant): Int =
        update(
            """
            UPDATE invoice_lines SET
                assigned_user_id = CAST(:user AS bigint),
                status = 'ASSIGNED',
                updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:line AS bigint) AND status = 'PENDING'
            """.trimIndent(),
            mapOf(
                "user" to userId,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "line" to lineId,
            ),
        )

    override fun setStatus(invoiceId: Long, status: String, at: Instant) {
        update(
            "UPDATE invoices SET status = CAST(:status AS invoice_status), updated_at = :at WHERE id = :id",
            mapOf("status" to status, "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC), "id" to invoiceId),
        )
    }

    override fun close(invoiceId: Long, actorId: Long, at: Instant) {
        update(
            """
            UPDATE invoices SET status = 'COMPLETED', reviewed_by = CAST(:actor AS bigint),
                   reviewed_at = CAST(:at AS timestamptz), updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint)
            """.trimIndent(),
            mapOf(
                "actor" to actorId,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "id" to invoiceId,
            ),
        )
    }

    override fun approveDiscrepancies(invoiceId: Long, approverId: Long, reason: String, at: Instant): Int =
        update(
            """
            UPDATE invoice_lines SET
                approved_by = CAST(:approver AS bigint),
                approved_at = CAST(:at AS timestamptz),
                adjustment_reason = CAST(CAST(:reason AS text) AS adjustment_reason),
                status = 'APPROVED',
                updated_at = CAST(:at AS timestamptz)
            WHERE invoice_id = CAST(:invoice AS bigint) AND deleted = FALSE AND status = 'DISCREPANCY'
            """.trimIndent(),
            mapOf(
                "approver" to approverId,
                "reason" to reason,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "invoice" to invoiceId,
            ),
        )

    override fun rejectDiscrepancies(invoiceId: Long, at: Instant): Int =
        update(
            """
            UPDATE invoice_lines SET
                physical_quantity = NULL,
                physical_box_quantity = NULL,
                captured_by = NULL,
                captured_at = NULL,
                status = 'PENDING',
                updated_at = CAST(:at AS timestamptz)
            WHERE invoice_id = CAST(:invoice AS bigint) AND deleted = FALSE AND status = 'DISCREPANCY'
            """.trimIndent(),
            mapOf("at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC), "invoice" to invoiceId),
        )

    override fun pendingApproval(): List<PendingApproval> =
        query(
            """
            SELECT i.id, i.invoice_number, i.receipt_date, i.status::text,
                   count(l.id) AS pending_lines,
                   COALESCE(sum(abs(l.difference)), 0) AS total_difference
            FROM invoices i
            JOIN invoice_lines l ON l.invoice_id = i.id AND l.deleted = FALSE
            WHERE i.deleted = FALSE AND l.status = 'DISCREPANCY'
            GROUP BY i.id, i.invoice_number, i.receipt_date, i.status
            ORDER BY i.receipt_date, i.id
            """.trimIndent(),
            emptyMap(),
        ) { rs, _ ->
            PendingApproval(
                invoiceId = rs.getLong("id"),
                invoiceNumber = rs.getString("invoice_number"),
                receiptDate = rs.getObject("receipt_date", java.time.LocalDate::class.java),
                status = rs.getString("status"),
                pendingLines = rs.getInt("pending_lines"),
                // The sum of ABSOLUTE differences: it stays positive even when goods are missing.
                totalDifference = rs.getBigDecimal("total_difference").toQuantity(),
            )
        }

    // ── The hook into the location (M02) ──────────────────────────────────

    override fun find(lineId: Long): InvoiceLine? =
        query("$LINE_COLUMNS $LINE_FROM WHERE l.deleted = FALSE AND l.id = :line", mapOf("line" to lineId)) { rs, _ ->
            rs.toLineView().line
        }.firstOrNull()

    override fun markLocated(lineId: Long, line: InvoiceLine, locationId: Long, at: Instant) {
        update(
            """
            UPDATE invoice_lines SET
                located_quantity = CAST(:located AS numeric),
                received_quantity = CAST(:received AS numeric),
                status = CAST(:status AS invoice_line_status),
                location_id = CAST(:location AS bigint),
                updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint)
            """.trimIndent(),
            mapOf(
                "located" to line.locatedQuantity.toSql(),
                "received" to line.receivedQuantity.toSql(),
                "status" to line.status.name,
                "location" to locationId,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "id" to lineId,
            ),
        )
    }

    // ── The catalog the creation needs ────────────────────────────────────

    override fun product(code: ProductCode): ReceivingProduct? =
        query(
            """
            SELECT p.id, p.unit_measure_id FROM products p
            WHERE p.product_code = :code AND p.active
            """.trimIndent(),
            mapOf("code" to code.value),
        ) { rs, _ -> ReceivingProduct(rs.getLong("id"), rs.getLong("unit_measure_id")) }.firstOrNull()

    override fun unitMeasureId(code: String): Long? =
        query("SELECT id FROM units_of_measure WHERE code = :code", mapOf("code" to code)) { rs, _ -> rs.getLong(1) }
            .firstOrNull()

    // ── Mapeo ───────────────────────────────────────────────────────────────

    private fun ResultSet.toHeader() = InvoiceHeader(
        id = getLong("id"),
        invoiceNumber = getString("invoice_number"),
        receiptDate = getObject("receipt_date", java.time.LocalDate::class.java),
        invoiceType = getString("invoice_type"),
        status = getString("status"),
        customerCode = getString("customer_code"),
        concept = getString("concept"),
        totalLines = getInt("total_lines"),
        pendingApprovalLines = getInt("pending_lines"),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )

    private fun ResultSet.toLineView(): InvoiceLineView {
        val quantity = getBigDecimal("quantity").toQuantity()
        val unitsPerBox = getBigDecimal("units_per_box")
            // The column is nullable and the domain requires the value: a row without it is a
            // data problem to be looked at, not something to round off silently.
            ?: error("Line ${getLong("id")} has no units per box: the packing cannot be validated.")
        val physical = getBigDecimal("physical_quantity").toQuantity().takeIf { !wasNull() }
        val physicalBoxes = getBigDecimal("physical_box_quantity")?.longValueExact()?.let(BoxQuantity::of)
        val located = getBigDecimal("located_quantity").toQuantity()
        val received = getBigDecimal("received_quantity").toQuantity()
        val assignedUser = getLong("assigned_user_id").takeUnless { wasNull() }
        val assignedUserName = getString("assigned_user_name")

        val location = getLong("location_id").takeUnless { wasNull() }?.let {
            Location(
                id = it,
                warehouseId = 0,
                hall = getString("location_hall"),
                code = getString("location_code"),
                position = getString("location_position"),
                kind = LocationKind.valueOf(getString("location_kind")),
            )
        }

        val line = InvoiceLine(
            id = getLong("id"),
            productCode = ProductCode.of(getString("product_code")),
            invoicedQuantity = quantity,
            unitsPerBox = UnitsPerBox.of(unitsPerBox.longValueExact()),
            status = InvoiceLineStatus.valueOf(getString("status")),
            physicalQuantity = physical,
            physicalBoxQuantity = physicalBoxes,
            locatedQuantity = located,
            receivedQuantity = received,
            assignedUserId = assignedUser,
            capturedBy = getLong("captured_by").takeUnless { wasNull() },
            approvedBy = getLong("approved_by").takeUnless { wasNull() },
            adjustmentReason = getString("adjustment_reason"),
            /*
             * The domain location is the legacy's flat code (`04BCA`, with the
             * level letter at the end), and the table has a foreign key: the level
             * is not in the row, so it **cannot be rebuilt** without inventing it.
             * It is left null, and the visible name —which is what the contract asks for— travels
             * in `locationDisplayName`, composed by the domain.
             */
            location = null,
        )

        return InvoiceLineView(
            line = line,
            productId = getLong("product_id"),
            productName = getString("product_name"),
            container = getString("container"),
            boxQuantity = getBigDecimal("box_quantity").toQuantity(),
            // The `difference` in the response is the database's GENERATED column: it is
            // the one that cannot drift out of sync.
            difference = getBigDecimal("difference").toQuantity().takeIf { !wasNull() },
            capturedByName = getString("captured_by_name"),
            capturedAt = getObject("captured_at", OffsetDateTime::class.java)?.toInstant(),
            approvedByName = getString("approved_by_name"),
            approvedAt = getObject("approved_at", OffsetDateTime::class.java)?.toInstant(),
            locationDisplayName = location?.displayName,
            assignedUserName = assignedUserName,
        )
    }

    private fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet, Int) -> T): List<T> {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.query(mapper).list()
    }

    /**
     * Executes a write and returns **how many rows it changed**.
     *
     * The number is not a detail: the transitions check it against the one that was
     * expected, and a difference means someone changed them at the same time.
     */
    private fun update(sql: String, params: Map<String, Any?>): Int {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.update()
    }

    /**
     * Executes a write and returns **how many rows it changed**.
     *
     * The number is not a detail: the transitions check it against the one that was
     * expected, and a difference means someone changed them at the same time.
     */
    private fun query(sql: String, params: Map<String, Any?>): Int {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.update()
    }

    private fun Quantity.toSql(): java.math.BigDecimal = java.math.BigDecimal.valueOf(value, Quantity.SCALE)

    private companion object {
        const val INVOICE_NUMBER_INDEX = "invoices_invoice_number_key"

        /**
         * The states that prevent closing, **composed from the domain**.
         *
         * Writing them by hand here would be the second definition of the rule, and the day
         * the domain changes the list would say one thing and the close another.
         */
        val BLOQUEANTES: String = InvoiceLineStatus.entries
            .filter { it.blocksInvoiceClosing }
            .joinToString(", ") { "'${it.name}'" }

        val HEADER_COLUMNS =
            """
            SELECT i.id, i.invoice_number, i.receipt_date, i.invoice_type::text AS invoice_type,
                   i.status::text AS status, i.customer_code, i.concept, i.created_at,
                   (SELECT count(*) FROM invoice_lines t WHERE t.invoice_id = i.id AND t.deleted = FALSE) AS total_lines,
                   (SELECT count(*) FROM invoice_lines t
                     WHERE t.invoice_id = i.id AND t.deleted = FALSE AND t.status IN ($BLOQUEANTES)) AS pending_lines
            """.trimIndent()

        const val HEADER_FROM = "FROM invoices i"

        val LINE_COLUMNS =
            """
            SELECT l.id, l.product_id, p.product_code, p.name AS product_name, l.container,
                   l.quantity, l.box_quantity, l.units_per_box, l.physical_quantity,
                   l.physical_box_quantity, l.located_quantity, l.received_quantity, l.difference,
                   l.status::text AS status, l.captured_by, cu.name AS captured_by_name,
                   l.captured_at, l.approved_by, au.name AS approved_by_name, l.approved_at,
                   l.adjustment_reason::text AS adjustment_reason, l.assigned_user_id,
                   asg.name AS assigned_user_name,
                   l.location_id, ll.hall AS location_hall, ll.code AS location_code,
                   ll.position AS location_position, ll.kind::text AS location_kind
            """.trimIndent()

        val LINE_FROM =
            """
            FROM invoice_lines l
            JOIN products p ON p.id = l.product_id
            LEFT JOIN users cu ON cu.id = l.captured_by
            LEFT JOIN users au ON au.id = l.approved_by
            LEFT JOIN users asg ON asg.id = l.assigned_user_id
            LEFT JOIN locations ll ON ll.id = l.location_id
            """.trimIndent()
    }
}
