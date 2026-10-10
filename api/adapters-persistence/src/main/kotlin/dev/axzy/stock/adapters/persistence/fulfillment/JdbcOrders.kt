package dev.axzy.stock.adapters.persistence.fulfillment

import dev.axzy.stock.adapters.persistence.shared.toQuantity
import dev.axzy.stock.application.fulfillment.InsertOrderResult
import dev.axzy.stock.application.fulfillment.OrderCatalog
import dev.axzy.stock.application.fulfillment.OrderDetail
import dev.axzy.stock.application.fulfillment.OrderDraft
import dev.axzy.stock.application.fulfillment.OrderFilter
import dev.axzy.stock.application.fulfillment.OrderHeader
import dev.axzy.stock.application.fulfillment.OrderLineView
import dev.axzy.stock.application.fulfillment.OrderLocation
import dev.axzy.stock.application.fulfillment.OrderProduct
import dev.axzy.stock.application.fulfillment.Orders
import dev.axzy.stock.application.fulfillment.ResolvedOrderLine
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.fulfillment.OrderLine
import dev.axzy.stock.domain.fulfillment.OrderLineStatus
import dev.axzy.stock.domain.fulfillment.OrderStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Las órdenes de surtido sobre PostgreSQL (docs/api/surtido.md).
 *
 * Dos cosas se componen **desde el dominio** en vez de escribirse aquí:
 *
 * · los estados que admiten empaque ([OrderStatus.allowsPackaging]) — la lista de la
 *   que se puede reclamar una confirmación;
 * · el estado de cada línea, que la tabla escribe pero el dominio nombra.
 *
 * Escribirlos a mano sería la segunda definición de la misma regla.
 */
class JdbcOrders(private val jdbc: JdbcClient) : Orders, OrderCatalog {

    override fun page(filter: OrderFilter, request: PageRequest): Page<OrderHeader> {
        val where = mutableListOf("o.deleted = FALSE")
        val params = mutableMapOf<String, Any?>()

        filter.status?.let {
            where += "o.status = CAST(:status AS order_status)"
            params["status"] = it
        }
        filter.query?.let {
            where += "(o.order_number ILIKE :q OR o.customer_code ILIKE :q OR o.concept ILIKE :q)"
            params["q"] = "%${it.trim()}%"
        }
        val clause = "WHERE " + where.joinToString(" AND ")

        val total = query("SELECT count(*) FROM orders o $clause", params) { rs, _ -> rs.getLong(1) }.single()
        val items = query(
            "$HEADER_COLUMNS $HEADER_FROM $clause ORDER BY o.id DESC LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toHeader() }

        return Page(items, request.page, request.size, total)
    }

    override fun detail(id: Long): OrderDetail? {
        val header = query(
            "$HEADER_COLUMNS $HEADER_FROM WHERE o.deleted = FALSE AND o.id = :id",
            mapOf("id" to id),
        ) { rs, _ -> rs.toHeader() }.firstOrNull() ?: return null

        val lines = query(
            "$LINE_COLUMNS $LINE_FROM WHERE l.deleted = FALSE AND l.order_id = :id ORDER BY l.id",
            mapOf("id" to id),
        ) { rs, _ -> rs.toLineView() }

        return OrderDetail(header, lines)
    }

    override fun orderNumberOf(id: Long): String? =
        query("SELECT order_number FROM orders WHERE id = :id", mapOf("id" to id)) { rs, _ -> rs.getString(1) }
            .firstOrNull()

    override fun numberExists(orderNumber: String): Boolean =
        query(
            "SELECT 1 FROM orders WHERE order_number = :number AND deleted = FALSE",
            mapOf("number" to orderNumber),
        ) { _, _ -> 1 }.isNotEmpty()

    /**
     * El correlativo del año.
     *
     * Se calcula sobre el mayor existente y no con una secuencia: el número tiene que
     * ser legible (`ORD-2026-0042`) y el hueco que deja una orden anulada no importa.
     * Dos altas simultáneas pueden calcular el mismo: el índice único lo decide y el
     * alta devuelve `DUPLICATE_ORDER_NUMBER`.
     */
    override fun nextOrderNumber(year: Int): String {
        val prefix = "ORD-$year-"
        val next = query(
            "SELECT COALESCE(max(substring(order_number from ${prefix.length + 1})::int), 0) + 1 " +
                "FROM orders WHERE order_number LIKE :prefix",
            mapOf("prefix" to "$prefix%"),
        ) { rs, _ -> rs.getInt(1) }.single()
        return prefix + next.toString().padStart(4, '0')
    }

    override fun insert(draft: OrderDraft, lines: List<ResolvedOrderLine>): InsertOrderResult {
        val orderId = try {
            query(
                """
                INSERT INTO orders (order_number, packaging_date, status, customer_code, concept)
                VALUES (:number, CAST(:date AS date), 'PENDING', :customer, :concept)
                RETURNING id
                """.trimIndent(),
                mapOf(
                    "number" to draft.orderNumber,
                    "date" to draft.packagingDate?.toString(),
                    "customer" to draft.customerCode,
                    "concept" to draft.concept,
                ),
            ) { rs, _ -> rs.getLong(1) }.single()
        } catch (error: DataIntegrityViolationException) {
            if (error.message?.contains(ORDER_NUMBER_INDEX) == true) return InsertOrderResult.DuplicateNumber
            throw error
        }

        // Una sola sentencia para todas las líneas (R-P9). El orden de los parámetros
        // sigue el de las COLUMNAS: invertirlo mete el id de la orden en el producto y
        // la clave foránea lo caza solo cuando hay más de una orden.
        val values = lines.indices.joinToString(", ") { index ->
            "(:order, :product$index, :unit$index, :quantity$index)"
        }
        val params = mutableMapOf<String, Any?>("order" to orderId)
        lines.forEachIndexed { index, line ->
            params["product$index"] = line.productId
            params["unit$index"] = line.unitMeasureId
            params["quantity$index"] = line.quantity.toSql()
        }
        update(
            """
            INSERT INTO order_lines (order_id, product_id, unit_measure_id, quantity)
            VALUES $values
            """.trimIndent(),
            params,
        )

        return InsertOrderResult.Inserted(orderId)
    }

    override fun lineOf(orderId: Long, lineId: Long): OrderLineView? =
        query(
            "$LINE_COLUMNS $LINE_FROM WHERE l.deleted = FALSE AND l.order_id = :order AND l.id = :line",
            mapOf("order" to orderId, "line" to lineId),
        ) { rs, _ -> rs.toLineView() }.firstOrNull()

    override fun replaceLine(line: OrderLine, locationId: Long?, at: Instant) {
        update(
            """
            UPDATE order_lines SET
                supplied_quantity = CAST(:supplied AS numeric),
                supplied = CAST(:complete AS boolean),
                status = CAST(:status AS order_line_status),
                location_id = CAST(:location AS bigint),
                updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint)
            """.trimIndent(),
            mapOf(
                "supplied" to line.suppliedQuantity.toSql(),
                "complete" to line.isComplete,
                "status" to line.status.name,
                "location" to locationId,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "id" to line.id,
            ),
        )
    }

    override fun assignLine(lineId: Long, userId: Long, locationId: Long?, at: Instant): Int =
        update(
            """
            UPDATE order_lines SET
                assigned_user_id = CAST(:user AS bigint),
                location_id = COALESCE(CAST(:location AS bigint), location_id),
                status = 'ASSIGNED',
                updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:line AS bigint)
              AND status IN ('PENDING', 'ASSIGNED')
            """.trimIndent(),
            mapOf(
                "user" to userId,
                "location" to locationId,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "line" to lineId,
            ),
        )

    override fun setStatus(orderId: Long, status: String, at: Instant) {
        update(
            "UPDATE orders SET status = CAST(:status AS order_status), updated_at = CAST(:at AS timestamptz) " +
                "WHERE id = CAST(:id AS bigint)",
            mapOf("status" to status, "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC), "id" to orderId),
        )
    }

    override fun claimConfirmation(orderId: Long, at: Instant): Int =
        update(
            """
            UPDATE orders SET status = 'PENDING_TO_OUT', updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint) AND status IN ($CONFIRMABLE)
            """.trimIndent(),
            mapOf("at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC), "id" to orderId),
        )

    override fun recordExport(orderId: Long, converted: Boolean, status: String, error: String?, at: Instant) {
        update(
            """
            UPDATE orders SET
                converted = CAST(:converted AS boolean),
                converted_at = CASE WHEN CAST(:converted AS boolean) THEN CAST(:at AS timestamptz) ELSE converted_at END,
                export_status = CAST(:status AS text),
                export_attempts = export_attempts + 1,
                export_last_error = CAST(:error AS text),
                sent = CAST(:converted AS boolean),
                status = CASE WHEN CAST(:converted AS boolean) THEN 'COMPLETED' ELSE status END,
                updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint)
            """.trimIndent(),
            mapOf(
                "converted" to converted,
                "status" to status,
                "error" to error,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "id" to orderId,
            ),
        )
    }

    // ── El catálogo que necesita el surtido ─────────────────────────────────

    override fun product(code: ProductCode): OrderProduct? =
        query(
            "SELECT id, unit_measure_id FROM products WHERE product_code = :code AND active",
            mapOf("code" to code.value),
        ) { rs, _ -> OrderProduct(rs.getLong("id"), rs.getLong("unit_measure_id")) }.firstOrNull()

    override fun location(id: Long): OrderLocation? =
        query(
            """
            SELECT l.id, l.warehouse_id, l.hall, l.code, l.position, l.kind::text AS kind
            FROM locations l WHERE l.id = :id AND l.active
            """.trimIndent(),
            mapOf("id" to id),
        ) { rs, _ ->
            OrderLocation(
                id = rs.getLong("id"),
                warehouseId = rs.getLong("warehouse_id"),
                displayName = dev.axzy.stock.domain.catalog.Location(
                    id = rs.getLong("id"),
                    warehouseId = rs.getLong("warehouse_id"),
                    hall = rs.getString("hall"),
                    code = rs.getString("code"),
                    position = rs.getString("position"),
                    kind = dev.axzy.stock.domain.catalog.LocationKind.valueOf(rs.getString("kind")),
                ).displayName,
            )
        }.firstOrNull()

    // ── Mapeo ───────────────────────────────────────────────────────────────

    private fun ResultSet.toHeader() = OrderHeader(
        id = getLong("id"),
        orderNumber = getString("order_number"),
        packagingDate = getObject("packaging_date", LocalDate::class.java),
        status = getString("status"),
        customerCode = getString("customer_code"),
        concept = getString("concept"),
        converted = getBoolean("converted"),
        convertedAt = getObject("converted_at", OffsetDateTime::class.java)?.toInstant(),
        exportStatus = getString("export_status"),
        exportAttempts = getInt("export_attempts"),
        exportLastError = getString("export_last_error"),
        totalLines = getInt("total_lines"),
        packagedLines = getInt("packaged_lines"),
    )

    private fun ResultSet.toLineView(): OrderLineView {
        val quantity = getBigDecimal("quantity").toQuantity()
        val supplied = getBigDecimal("supplied_quantity").toQuantity()
        val line = OrderLine(
            id = getLong("id"),
            productId = getLong("product_id"),
            productCode = getString("product_code"),
            productName = getString("product_name"),
            unitMeasure = getString("unit_measure"),
            quantity = quantity,
            suppliedQuantity = supplied,
            status = OrderLineStatus.valueOf(getString("status")),
            assignedUserId = getLong("assigned_user_id").takeUnless { wasNull() },
            locationId = getLong("location_id").takeUnless { wasNull() },
        )
        return OrderLineView(
            line = line,
            unitMeasure = getString("unit_measure"),
            assignedUserName = getString("assigned_user_name"),
            locationDisplayName = locationName(),
        )
    }

    /** El nombre visible de la ubicación, compuesto por el dominio a partir de sus columnas. */
    private fun ResultSet.locationName(): String? {
        if (getString("location_hall") == null) return null
        return dev.axzy.stock.domain.catalog.Location(
            id = getLong("location_id"),
            warehouseId = 0,
            hall = getString("location_hall"),
            code = getString("location_code"),
            position = getString("location_position"),
            kind = dev.axzy.stock.domain.catalog.LocationKind.valueOf(getString("location_kind")),
        ).displayName
    }

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
        const val ORDER_NUMBER_INDEX = "orders_order_number_key"

        /**
         * Los estados desde los que se puede reclamar una confirmación, **compuestos
         * desde el dominio**: la lista y la regla no pueden separarse.
         */
        val CONFIRMABLE: String = OrderStatus.entries
            .filter { it.allowsPackaging }
            .joinToString(", ") { "'${it.name}'" }

        val HEADER_COLUMNS =
            """
            SELECT o.id, o.order_number, o.packaging_date, o.status::text AS status, o.customer_code,
                   o.concept, o.converted, o.converted_at, o.export_status, o.export_attempts,
                   o.export_last_error,
                   (SELECT count(*) FROM order_lines t WHERE t.order_id = o.id AND t.deleted = FALSE) AS total_lines,
                   (SELECT count(*) FROM order_lines t
                     WHERE t.order_id = o.id AND t.deleted = FALSE
                       AND t.supplied_quantity >= t.quantity) AS packaged_lines
            """.trimIndent()

        const val HEADER_FROM = "FROM orders o"

        val LINE_COLUMNS =
            """
            SELECT l.id, l.product_id, p.product_code, p.name AS product_name, u.code AS unit_measure,
                   l.quantity, l.supplied_quantity, l.status::text AS status, l.assigned_user_id,
                   au.name AS assigned_user_name, l.location_id, ll.hall AS location_hall,
                   ll.code AS location_code, ll.position AS location_position, ll.kind::text AS location_kind
            """.trimIndent()

        val LINE_FROM =
            """
            FROM order_lines l
            JOIN products p ON p.id = l.product_id
            JOIN units_of_measure u ON u.id = l.unit_measure_id
            LEFT JOIN users au ON au.id = l.assigned_user_id
            LEFT JOIN locations ll ON ll.id = l.location_id
            """.trimIndent()
    }
}
