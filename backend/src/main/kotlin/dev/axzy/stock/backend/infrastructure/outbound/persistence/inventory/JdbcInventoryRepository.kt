package dev.axzy.stock.backend.infrastructure.outbound.persistence.inventory

import dev.axzy.stock.backend.application.audit.AuditEntry
import dev.axzy.stock.backend.application.inventory.DivergenceDto
import dev.axzy.stock.backend.application.inventory.InventoryRepository
import dev.axzy.stock.backend.application.inventory.InventoryWork
import dev.axzy.stock.backend.application.inventory.KardexDto
import dev.axzy.stock.backend.application.inventory.KardexFilter
import dev.axzy.stock.backend.application.inventory.StockByLocationDto
import dev.axzy.stock.backend.application.inventory.StockFilter
import dev.axzy.stock.backend.application.inventory.StockLocation
import dev.axzy.stock.backend.application.inventory.StockMovement
import dev.axzy.stock.backend.application.shared.locationDisplayName
import dev.axzy.stock.backend.infrastructure.outbound.persistence.audit.AuditWriter
import dev.axzy.stock.backend.infrastructure.outbound.persistence.shared.setNullableLong
import dev.axzy.stock.backend.infrastructure.outbound.persistence.shared.toDecimal
import dev.axzy.stock.backend.infrastructure.outbound.persistence.shared.toQuantity
import dev.axzy.stock.backend.infrastructure.outbound.persistence.shared.transactional
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Persistencia del inventario en PostgreSQL.
 *
 * **Todo movimiento pasa por el motor SQL de la migración V6**
 * (`aplicar_movimiento_a_ubicacion`, `recalcular_total_producto`), en la transacción de quien
 * llama. Escribir las ubicaciones, el total y el kardex por separado desde el código dejaría el
 * inventario descuadrado ante un fallo a mitad, y nadie lo sabría hasta el conteo físico.
 */
class JdbcInventoryRepository(
    private val dataSource: DataSource,
    private val auditWriter: AuditWriter = AuditWriter(dataSource),
) : InventoryRepository {

    override fun stockByLocation(filter: StockFilter, offset: Int, limit: Int): List<StockByLocationDto> =
        dataSource.connection.use { connection ->
            val sql = buildString {
                append(
                    """
                    SELECT s.product_id, p.product_code, p.name AS product_name,
                           s.location_id, l.hall, l.code, l."position", l.kind,
                           s.warehouse_id, w.code AS warehouse_code, s.quantity
                    FROM stock_by_location s
                    JOIN products p ON p.id = s.product_id
                    JOIN locations l ON l.id = s.location_id
                    JOIN warehouses w ON w.id = s.warehouse_id
                    WHERE 1 = 1
                    """.trimIndent(),
                )
                appendStockFilter(filter)
                append(" ORDER BY p.product_code, l.hall, l.code, l.\"position\" LIMIT ? OFFSET ?")
            }

            connection.prepareStatement(sql).use { st ->
                var i = st.bindStockFilter(filter)
                st.setInt(i++, limit)
                st.setInt(i, offset)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toStockByLocationDto()) } }
            }
        }

    override fun countStock(filter: StockFilter): Long = dataSource.connection.use { connection ->
        val sql = buildString {
            append(
                """
                SELECT count(*)
                FROM stock_by_location s
                JOIN products p ON p.id = s.product_id
                JOIN locations l ON l.id = s.location_id
                WHERE 1 = 1
                """.trimIndent(),
            )
            appendStockFilter(filter)
        }

        connection.prepareStatement(sql).use { st ->
            st.bindStockFilter(filter)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    override fun kardex(filter: KardexFilter, offset: Int, limit: Int): List<KardexDto> =
        dataSource.connection.use { connection ->
            val sql = buildString {
                append(
                    """
                    SELECT k.id, k.moved_at, k.move_type, k.units, k.quantity_after,
                           p.product_code, p.name AS product_name,
                           k.from_location_id, k.to_location_id, k.location_id,
                           lo.hall AS from_hall, lo.code AS from_code, lo."position" AS from_pos,
                           lo.kind AS from_kind,
                           ld.hall AS to_hall, ld.code AS to_code, ld."position" AS to_pos,
                           ld.kind AS to_kind,
                           k.container, k.order_number, k.adjustment_reason,
                           k.adjustment_origin, k.user_id, u.name AS user_name, k.note
                    FROM kardex k
                    JOIN products p ON p.id = k.product_id
                    LEFT JOIN locations lo ON lo.id = k.from_location_id
                    LEFT JOIN locations ld ON ld.id = COALESCE(k.to_location_id, k.location_id)
                    LEFT JOIN users u ON u.id = k.user_id
                    WHERE 1 = 1
                    """.trimIndent(),
                )
                appendKardexFilter(filter)
                append(" ORDER BY k.moved_at DESC, k.id DESC LIMIT ? OFFSET ?")
            }

            connection.prepareStatement(sql).use { st ->
                var i = st.bindKardexFilter(filter)
                st.setInt(i++, limit)
                st.setInt(i, offset)

                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            // El nombre visible lo compone una sola función: si el mapper lo
                            // leyera de las columnas crudas, repetiría la regla.
                            val from = rs.getString("from_hall")?.let {
                                locationDisplayName(it, rs.getString("from_code"), rs.getString("from_pos"), rs.getString("from_kind"))
                            }
                            val towards = rs.getString("to_hall")?.let {
                                locationDisplayName(it, rs.getString("to_code"), rs.getString("to_pos"), rs.getString("to_kind"))
                            }
                            add(rs.toKardexDto(fromLocationDisplayName = from, toLocationDisplayName = towards))
                        }
                    }
                }
            }
        }

    /**
     * El conteo aplica **el mismo filtro que el listado**, fechas incluidas. Cuando no lo hacía,
     * un rango sin resultados devolvía `items: []` con `totalItems: 1064`, y el cliente pintaba
     * «1.064 movimientos» sobre una lista vacía.
     */
    override fun countKardex(filter: KardexFilter): Long = dataSource.connection.use { connection ->
        val sql = buildString {
            append("SELECT count(*) FROM kardex k JOIN products p ON p.id = k.product_id WHERE 1 = 1")
            appendKardexFilter(filter)
        }
        connection.prepareStatement(sql).use { st ->
            st.bindKardexFilter(filter)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    override fun divergences(): List<DivergenceDto> = dataSource.connection.use { connection ->
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM comprobar_proyecciones()").use { rs ->
                buildList { while (rs.next()) add(rs.toDivergenceDto()) }
            }
        }
    }

    override fun <T> inTransaction(work: InventoryWork.() -> T): T =
        dataSource.transactional { connection -> JdbcInventoryWork(connection, auditWriter).work() }
}

// ── Filtros: el listado y su conteo comparten la misma condición ──────────────

private fun StringBuilder.appendStockFilter(filter: StockFilter) {
    if (filter.productCode != null) append(" AND p.product_code = ?")
    if (filter.locationCode != null) append(LOCATION_NAME_MATCHES)
    if (filter.onlyWithStock) append(" AND s.quantity > 0")
}

/** Enlaza los parámetros del filtro y devuelve el índice del siguiente. */
private fun PreparedStatement.bindStockFilter(filter: StockFilter): Int {
    var i = 1
    if (filter.productCode != null) setString(i++, filter.productCode)
    if (filter.locationCode != null) {
        // Dos parámetros, uno por cada forma de nombrar la ubicación.
        setString(i++, filter.locationCode)
        setString(i++, filter.locationCode)
    }
    return i
}

private fun StringBuilder.appendKardexFilter(filter: KardexFilter) {
    if (filter.productCode != null) append(" AND p.product_code = ?")
    if (filter.type != null) append(" AND k.move_type = ?::movement_type")
    if (filter.from != null) append(" AND k.moved_at >= ?")
    if (filter.until != null) append(" AND k.moved_at <= ?")
}

private fun PreparedStatement.bindKardexFilter(filter: KardexFilter): Int {
    var i = 1
    if (filter.productCode != null) setString(i++, filter.productCode)
    if (filter.type != null) setString(i++, filter.type)
    if (filter.from != null) setTimestamp(i++, Timestamp.from(filter.from))
    if (filter.until != null) setTimestamp(i++, Timestamp.from(filter.until))
    return i
}

/**
 * Una ubicación se puede nombrar de dos formas, porque el usuario copia lo que ve en la
 * pantalla: las tres partes concatenadas, o el nombre visible de una especial (`SIN-UBICAR`).
 *
 * **Sin `IN` sobre una lista fija.** La primera versión hacía
 * `UPPER(REPLACE(?,'-','')) IN ('SINUBICAR','RECEPCION')`, y como `RECEPCION` está en esa lista,
 * filtrar por `RECEPCION` devolvía TODAS las filas: un `IN` con constantes no compara con la
 * fila, usa el parámetro como permiso.
 */
private const val LOCATION_NAME_MATCHES =
    " AND (UPPER(REPLACE(l.hall || l.code || l.\"position\", '-', '')) " +
        "= UPPER(REPLACE(?, '-', ' ')) " +
        "OR (CASE l.kind WHEN 'RECEIVING' THEN 'RECEPCION' " +
        "WHEN 'UNASSIGNED' THEN 'SINUBICAR' END) = " +
        "UPPER(REPLACE(?, '-', '')))"

/**
 * Las operaciones de [InventoryWork] sobre una conexión. Vive y muere con la transacción que la
 * crea: no guarda nada entre llamadas.
 */
private class JdbcInventoryWork(
    private val connection: Connection,
    private val auditWriter: AuditWriter,
) : InventoryWork {

    override fun activeProductId(code: ProductCode): Long? =
        connection.prepareStatement("SELECT id FROM products WHERE product_code = ? AND active").use { st ->
            st.setString(1, code.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }

    /**
     * Resuelve una ubicación por su código.
     *
     * Las especiales (`RECEPCION`, `SIN-UBICAR`) se crearon con las tres partes iguales, así que
     * su concatenación mide 27 caracteres y [LocationCode] admite 24: no existía ningún código
     * con el que encontrarlas. La concatenación solo tiene sentido para un estante (`04BCA`);
     * una especial **se identifica por su nombre visible**, y por eso se resuelve por `kind`.
     */
    override fun activeLocation(code: LocationCode): StockLocation? =
        connection.prepareStatement(
            """
            SELECT id, warehouse_id FROM locations
            WHERE (
                    (hall || code || "position") = ?
                 OR (CASE kind
                        WHEN 'RECEIVING'  THEN 'RECEPCION'
                        WHEN 'UNASSIGNED' THEN 'SINUBICAR'
                     END) = UPPER(REPLACE(?, '-', ''))
                  )
              AND active
            """.trimIndent(),
        ).use { st ->
            st.setString(1, code.value)
            st.setString(2, code.value)
            st.executeQuery().use { rs ->
                if (rs.next()) StockLocation(rs.getLong("id"), rs.getLong("warehouse_id")) else null
            }
        }

    override fun balance(productId: Long, locationId: Long): Quantity =
        connection.prepareStatement(
            "SELECT quantity FROM stock_by_location WHERE product_id = ? AND location_id = ?",
        ).use { st ->
            st.setLong(1, productId)
            st.setLong(2, locationId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getBigDecimal("quantity").toQuantity() else Quantity.ZERO }
        }

    override fun total(productId: Long): Quantity =
        connection.prepareStatement("SELECT quantity FROM product_stock WHERE product_id = ?").use { st ->
            st.setLong(1, productId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getBigDecimal("quantity").toQuantity() else Quantity.ZERO }
        }

    /**
     * El motor ajusta las ubicaciones y recalcula el total **antes** de escribir el kardex: la
     * fila guarda en `quantity_after` el total que quedó, y lo lee de `product_stock`.
     */
    override fun move(movement: StockMovement): Long {
        val units = movement.units.toDecimal()

        movement.fromLocationId?.let { applyToLocation(movement, it, units.negate()) }
        movement.toLocationId?.let { applyToLocation(movement, it, units) }
        recalculateTotal(movement.productId)

        val sql = """
            INSERT INTO kardex (
                product_id, warehouse_id, move_type, units, quantity_after,
                location_id, from_location_id, to_location_id,
                adjustment_reason, adjustment_origin,
                user_id, approved_by, approved_at, client_uuid, note
            )
            SELECT ?, ?, ?::movement_type, ?, ps.quantity,
                   ?, ?, ?, ?::adjustment_reason, ?::adjustment_origin,
                   ?, ?, ?, ?::uuid, ?
            FROM product_stock ps WHERE ps.product_id = ?
            RETURNING id
        """.trimIndent()

        return connection.prepareStatement(sql).use { st ->
            var i = 1
            st.setLong(i++, movement.productId)
            st.setLong(i++, movement.warehouseId)
            st.setString(i++, movement.type.name)
            st.setBigDecimal(i++, units)
            st.setNullableLong(i++, movement.toLocationId)
            st.setNullableLong(i++, movement.fromLocationId)
            st.setNullableLong(i++, movement.toLocationId)
            st.setString(i++, movement.reason?.name)
            st.setString(i++, movement.origin?.name)
            st.setNullableLong(i++, movement.userId)
            st.setNullableLong(i++, movement.approverId)
            st.setTimestamp(i++, movement.approverId?.let { Timestamp.from(Instant.now()) })
            st.setString(i++, movement.idempotencyKey ?: UUID.randomUUID().toString())
            st.setString(i++, movement.note)
            st.setLong(i, movement.productId)
            st.executeQuery().use { rs ->
                check(rs.next()) { "El producto ${movement.productId} no tiene fila en product_stock." }
                rs.getLong("id")
            }
        }
    }

    override fun audit(entry: AuditEntry): Long = auditWriter.record(connection, entry)

    private fun applyToLocation(movement: StockMovement, locationId: Long, delta: BigDecimal) {
        connection.prepareStatement("SELECT aplicar_movimiento_a_ubicacion(?, ?, ?, ?, FALSE)").use { st ->
            st.setLong(1, movement.productId)
            st.setLong(2, locationId)
            st.setLong(3, movement.warehouseId)
            st.setBigDecimal(4, delta)
            st.execute()
        }
    }

    private fun recalculateTotal(productId: Long) {
        connection.prepareStatement("SELECT recalcular_total_producto(?)").use { st ->
            st.setLong(1, productId)
            st.execute()
        }
    }
}
