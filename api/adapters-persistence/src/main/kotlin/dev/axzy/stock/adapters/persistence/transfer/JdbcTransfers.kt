package dev.axzy.stock.adapters.persistence.transfer

import dev.axzy.stock.adapters.persistence.shared.toQuantity
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.transfer.InTransitRow
import dev.axzy.stock.application.transfer.InsertTransferResult
import dev.axzy.stock.application.transfer.ResolvedTransferLine
import dev.axzy.stock.application.transfer.TransferCatalog
import dev.axzy.stock.application.transfer.TransferDetail
import dev.axzy.stock.application.transfer.TransferDraft
import dev.axzy.stock.application.transfer.TransferFilter
import dev.axzy.stock.application.transfer.TransferHeader
import dev.axzy.stock.application.transfer.TransferLineView
import dev.axzy.stock.application.transfer.TransferProduct
import dev.axzy.stock.application.transfer.TransferWarehouse
import dev.axzy.stock.application.transfer.Transfers
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.transfer.TransferLine
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Los traslados sobre PostgreSQL (docs/api/traslados.md).
 *
 * **Las tres operaciones que mueven mercancía llaman a las funciones del motor**
 * (`dispatch_transfer`, `receive_transfer`, `cancel_transfer`, migración V13). Este
 * archivo no tiene ningún `UPDATE stock_by_location`: si lo tuviera, sería la segunda
 * ruta de escritura al inventario y la guardia `InventoryWriteGuardTest` lo cazaría.
 *
 * El `totalUnits` de la cabecera se suma en la base, no en Kotlin: un total que se
 * calcula en dos sitios acaba diciendo cosas distintas.
 */
class JdbcTransfers(private val jdbc: JdbcClient) : Transfers, TransferCatalog {

    override fun page(filter: TransferFilter, request: PageRequest): Page<TransferHeader> {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()

        filter.status?.let {
            where += "t.status = CAST(:status AS transfer_status)"
            params["status"] = it
        }
        filter.warehouseId?.let {
            where += "(t.from_warehouse_id = :warehouse OR t.to_warehouse_id = :warehouse)"
            params["warehouse"] = it
        }
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")

        val total = query("SELECT count(*) $HEADER_FROM $clause", params) { rs, _ -> rs.getLong(1) }.single()
        val items = query(
            "$HEADER_COLUMNS $HEADER_FROM $clause ORDER BY t.id DESC LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toHeader() }

        return Page(items, request.page, request.size, total)
    }

    override fun detail(id: Long): TransferDetail? {
        val header = query("$HEADER_COLUMNS $HEADER_FROM WHERE t.id = :id", mapOf("id" to id)) { rs, _ -> rs.toHeader() }
            .firstOrNull() ?: return null
        val lines = query(
            "$LINE_COLUMNS $LINE_FROM WHERE l.transfer_id = :id ORDER BY l.id",
            mapOf("id" to id),
        ) { rs, _ -> rs.toLineView() }
        return TransferDetail(header, lines)
    }

    override fun numberExists(code: String): Boolean =
        query("SELECT 1 FROM transfers WHERE code = :code", mapOf("code" to code)) { _, _ -> 1 }.isNotEmpty()

    override fun nextCode(year: Int): String {
        val prefix = "TR-$year-"
        val next = query(
            "SELECT COALESCE(max(substring(code from ${prefix.length + 1})::int), 0) + 1 " +
                "FROM transfers WHERE code LIKE :prefix",
            mapOf("prefix" to "$prefix%"),
        ) { rs, _ -> rs.getInt(1) }.single()
        return prefix + next.toString().padStart(4, '0')
    }

    override fun insert(draft: TransferDraft, lines: List<ResolvedTransferLine>): InsertTransferResult {
        val transferId = try {
            query(
                """
                INSERT INTO transfers (code, from_warehouse_id, to_warehouse_id, status, note)
                VALUES (:code, :from, :to, 'DRAFT', :note)
                RETURNING id
                """.trimIndent(),
                mapOf(
                    "code" to draft.code,
                    "from" to draft.fromWarehouseId,
                    "to" to draft.toWarehouseId,
                    "note" to draft.note,
                ),
            ) { rs, _ -> rs.getLong(1) }.single()
        } catch (error: DataIntegrityViolationException) {
            if (error.message?.contains(TRANSFER_CODE_INDEX) == true) return InsertTransferResult.DuplicateCode
            throw error
        }

        // Los parámetros siguen el orden de las COLUMNAS: invertirlos mete el id del
        // traslado en el producto, y la clave foránea solo lo caza con más de un alta.
        val values = lines.indices.joinToString(", ") { index ->
            "(:transfer, :product$index, :fromLocation$index, :quantity$index, :note$index)"
        }
        val params = mutableMapOf<String, Any?>("transfer" to transferId)
        lines.forEachIndexed { index, line ->
            params["product$index"] = line.productId
            params["fromLocation$index"] = line.fromLocationId
            params["quantity$index"] = line.quantity.toSql()
            params["note$index"] = line.note
        }
        update(
            """
            INSERT INTO transfer_lines (transfer_id, product_id, from_location_id, quantity, note)
            VALUES $values
            """.trimIndent(),
            params,
        )
        return InsertTransferResult.Inserted(transferId)
    }

    override fun dispatch(transferId: Long, userId: Long): Int =
        function("dispatch_transfer", transferId, userId)

    override fun receive(transferId: Long, userId: Long): Int =
        function("receive_transfer", transferId, userId)

    override fun cancel(transferId: Long, userId: Long): Int =
        function("cancel_transfer", transferId, userId)

    override fun openTransitLines(transferId: Long): Int =
        query(
            "SELECT count(*) FROM stock_in_transit WHERE transfer_id = :id AND received_at IS NULL",
            mapOf("id" to transferId),
        ) { rs, _ -> rs.getInt(1) }.single()

    override fun inTransit(): List<InTransitRow> =
        query(
            """
            SELECT t.id, t.code, t.status::text AS status, t.dispatched_at, t.dispatched_by,
                   u.name AS dispatched_by_name,
                   fw.code AS from_warehouse_code, tw.code AS to_warehouse_code,
                   (SELECT count(*) FROM transfer_lines l WHERE l.transfer_id = t.id) AS line_count,
                   (SELECT COALESCE(sum(l.quantity), 0) FROM transfer_lines l WHERE l.transfer_id = t.id) AS total_units
            FROM transfers t
            JOIN warehouses fw ON fw.id = t.from_warehouse_id
            JOIN warehouses tw ON tw.id = t.to_warehouse_id
            LEFT JOIN users u ON u.id = t.dispatched_by
            WHERE t.status IN ('DISPATCHED', 'IN_TRANSIT')
            ORDER BY t.dispatched_at, t.id
            """.trimIndent(),
            emptyMap(),
        ) { rs, _ ->
            val dispatchedBy = rs.getLong("dispatched_by").takeUnless { rs.wasNull() }
            InTransitRow(
                id = rs.getLong("id"),
                code = rs.getString("code"),
                fromWarehouseCode = rs.getString("from_warehouse_code"),
                toWarehouseCode = rs.getString("to_warehouse_code"),
                status = rs.getString("status"),
                lineCount = rs.getInt("line_count"),
                totalUnits = rs.getBigDecimal("total_units").toQuantity(),
                dispatchedAt = rs.getObject("dispatched_at", OffsetDateTime::class.java)?.toInstant(),
                // Los días los pone el caso de uso, con el reloj de la aplicación.
                daysInTransit = 0,
                dispatchedBy = dispatchedBy,
                dispatchedByName = rs.getString("dispatched_by_name"),
            )
        }

    // ── El catálogo ─────────────────────────────────────────────────────────

    override fun product(code: ProductCode): TransferProduct? =
        query(
            "SELECT id, product_code, name FROM products WHERE product_code = :code AND active",
            mapOf("code" to code.value),
        ) { rs, _ ->
            TransferProduct(rs.getLong("id"), rs.getString("product_code"), rs.getString("name"))
        }.firstOrNull()

    override fun warehouse(id: Long): TransferWarehouse? =
        query("SELECT id, code FROM warehouses WHERE id = :id AND active", mapOf("id" to id)) { rs, _ ->
            TransferWarehouse(rs.getLong("id"), rs.getString("code"))
        }.firstOrNull()

    override fun receivingLocationId(warehouseId: Long): Long? =
        query(
            "SELECT receiving_location(:warehouse) AS id",
            mapOf("warehouse" to warehouseId),
        ) { rs, _ -> rs.getLong("id") }.firstOrNull()

    // ── Mapeo ───────────────────────────────────────────────────────────────

    private fun ResultSet.toHeader() = TransferHeader(
        id = getLong("id"),
        code = getString("code"),
        fromWarehouseId = getLong("from_warehouse_id"),
        fromWarehouseCode = getString("from_warehouse_code"),
        toWarehouseId = getLong("to_warehouse_id"),
        toWarehouseCode = getString("to_warehouse_code"),
        status = getString("status"),
        lineCount = getInt("line_count"),
        totalUnits = getBigDecimal("total_units").toQuantity(),
        note = getString("note"),
        dispatchedBy = getLong("dispatched_by").takeUnless { wasNull() },
        dispatchedByName = getString("dispatched_by_name"),
        dispatchedAt = getObject("dispatched_at", OffsetDateTime::class.java)?.toInstant(),
        receivedBy = getLong("received_by").takeUnless { wasNull() },
        receivedByName = getString("received_by_name"),
        receivedAt = getObject("received_at", OffsetDateTime::class.java)?.toInstant(),
    )

    private fun ResultSet.toLineView(): TransferLineView {
        val fromLocationId = getLong("from_location_id").takeUnless { wasNull() }
        val displayName = if (getString("from_location_hall") == null) {
            null
        } else {
            Location(
                id = fromLocationId ?: 0,
                warehouseId = 0,
                hall = getString("from_location_hall"),
                code = getString("from_location_code"),
                position = getString("from_location_position"),
                kind = LocationKind.valueOf(getString("from_location_kind")),
            ).displayName
        }
        return TransferLineView(
            TransferLine(
                id = getLong("line_id"),
                productId = getLong("product_id"),
                productCode = getString("product_code"),
                productName = getString("product_name"),
                quantity = getBigDecimal("quantity").toQuantity(),
                fromLocationId = fromLocationId,
                fromLocationDisplayName = displayName,
                note = getString("line_note"),
            ),
        )
    }

    private fun function(name: String, transferId: Long, userId: Long): Int =
        query(
            "SELECT $name(:transfer, :user)",
            mapOf("transfer" to transferId, "user" to userId),
        ) { rs, _ -> rs.getInt(1) }.single()

    private fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet, Int) -> T): List<T> {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.query(mapper).list()
    }

    private fun update(sql: String, params: Map<String, Any?>): Int {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.update()
    }

    private fun Quantity.toSql(): BigDecimal = BigDecimal.valueOf(value, Quantity.SCALE)

    private companion object {
        const val TRANSFER_CODE_INDEX = "transfers_code_key"

        val HEADER_COLUMNS =
            """
            SELECT t.id, t.code, t.from_warehouse_id, fw.code AS from_warehouse_code,
                   t.to_warehouse_id, tw.code AS to_warehouse_code, t.status::text AS status,
                   t.note, t.dispatched_by, du.name AS dispatched_by_name, t.dispatched_at,
                   t.received_by, ru.name AS received_by_name, t.received_at,
                   (SELECT count(*) FROM transfer_lines l WHERE l.transfer_id = t.id) AS line_count,
                   (SELECT COALESCE(sum(l.quantity), 0) FROM transfer_lines l WHERE l.transfer_id = t.id) AS total_units
            """.trimIndent()

        val HEADER_FROM =
            """
            FROM transfers t
            JOIN warehouses fw ON fw.id = t.from_warehouse_id
            JOIN warehouses tw ON tw.id = t.to_warehouse_id
            LEFT JOIN users du ON du.id = t.dispatched_by
            LEFT JOIN users ru ON ru.id = t.received_by
            """.trimIndent()

        val LINE_COLUMNS =
            """
            SELECT l.id AS line_id, l.product_id, p.product_code, p.name AS product_name,
                   l.quantity, l.from_location_id, l.note AS line_note,
                   ll.hall AS from_location_hall, ll.code AS from_location_code,
                   ll.position AS from_location_position, ll.kind::text AS from_location_kind
            """.trimIndent()

        val LINE_FROM =
            """
            FROM transfer_lines l
            JOIN products p ON p.id = l.product_id
            LEFT JOIN locations ll ON ll.id = l.from_location_id
            """.trimIndent()
    }
}
