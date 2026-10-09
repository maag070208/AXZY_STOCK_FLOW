package dev.axzy.stock.backend.infrastructure.outbound.persistence.transfer

import dev.axzy.stock.backend.application.transfer.ActiveProduct
import dev.axzy.stock.backend.application.transfer.InTransitRow
import dev.axzy.stock.backend.application.transfer.OpenTransit
import dev.axzy.stock.backend.application.transfer.TransferDetail
import dev.axzy.stock.backend.application.transfer.TransferFilter
import dev.axzy.stock.backend.application.transfer.TransferHeader
import dev.axzy.stock.backend.application.transfer.TransferLineView
import dev.axzy.stock.backend.application.transfer.TransferRepository
import dev.axzy.stock.backend.application.transfer.TransferRoute
import dev.axzy.stock.backend.application.transfer.TransferWork
import dev.axzy.stock.backend.audit.AuditWriter
import dev.axzy.stock.domain.transfer.TransferLine
import dev.axzy.stock.domain.transfer.TransferStatus
import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Persistencia de traslados en PostgreSQL.
 *
 * El SQL y el motor (`registrar_movimiento`, `recalcular_total_producto`) son los mismos que
 * tenía el servicio: aquí solo cambian de sitio. La transacción conserva el patrón de siempre:
 * una conexión con `autoCommit = false`, commit al final, rollback ante cualquier fallo.
 */
class JdbcTransferRepository(
    private val dataSource: DataSource,
    private val writer: AuditWriter = AuditWriter(dataSource),
) : TransferRepository {

    override fun find(id: Long): TransferDetail? = dataSource.connection.use { connection ->
        val header = readHeader(connection, id) ?: return@use null
        TransferDetail(header, readViews(connection, id))
    }

    override fun list(filter: TransferFilter, offset: Int, limit: Int): List<TransferHeader> =
        dataSource.connection.use { connection ->
            val sql = buildString {
                append(HEADER_SELECT)
                append(" WHERE 1=1 ")
                if (filter.status != null) append(" AND t.status = ?::transfer_status ")
                if (filter.warehouseId != null) append(" AND (t.from_warehouse_id = ? OR t.to_warehouse_id = ?) ")
                append(" ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?")
            }

            connection.prepareStatement(sql).use { st ->
                var i = 1
                if (filter.status != null) st.setString(i++, filter.status)
                if (filter.warehouseId != null) {
                    st.setLong(i++, filter.warehouseId)
                    st.setLong(i++, filter.warehouseId)
                }
                st.setInt(i++, limit)
                st.setInt(i, offset)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toHeader()) } }
            }
        }

    override fun count(filter: TransferFilter): Long = dataSource.connection.use { connection ->
        val sql = buildString {
            append("SELECT count(*) FROM transfers t WHERE 1=1 ")
            if (filter.status != null) append(" AND t.status = ?::transfer_status ")
            if (filter.warehouseId != null) append(" AND (t.from_warehouse_id = ? OR t.to_warehouse_id = ?) ")
        }
        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (filter.status != null) st.setString(i++, filter.status)
            if (filter.warehouseId != null) {
                st.setLong(i++, filter.warehouseId)
                st.setLong(i, filter.warehouseId)
            }
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    override fun inTransit(): List<InTransitRow> = dataSource.connection.use { connection ->
        val sql = """
            SELECT t.id, t.code, t.status,
                   wo.code AS from_code, wd.code AS to_code,
                   t.dispatched_at, t.dispatched_by, u.name AS dispatched_by_name,
                   COALESCE(l.line_count, 0) AS line_count,
                   COALESCE(l.total_units, 0) AS total_units,
                   EXTRACT(DAY FROM (now() - t.dispatched_at))::bigint AS days_in_transit
            FROM transfers t
            JOIN warehouses wo ON wo.id = t.from_warehouse_id
            JOIN warehouses wd ON wd.id = t.to_warehouse_id
            LEFT JOIN users u ON u.id = t.dispatched_by
            LEFT JOIN (
                SELECT transfer_id, count(*) AS line_count, SUM(quantity) AS total_units
                FROM transfer_lines GROUP BY transfer_id
            ) l ON l.transfer_id = t.id
            WHERE t.status IN ('DISPATCHED', 'IN_TRANSIT')
            ORDER BY t.dispatched_at ASC, t.id ASC
        """.trimIndent()

        connection.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            InTransitRow(
                                id = rs.getLong("id"),
                                code = rs.getString("code"),
                                fromWarehouseCode = rs.getString("from_code"),
                                toWarehouseCode = rs.getString("to_code"),
                                status = rs.getString("status"),
                                lineCount = rs.getInt("line_count"),
                                totalUnits = rs.getBigDecimal("total_units"),
                                // Solo entran estados despachados: la fecha siempre existe.
                                dispatchedAt = rs.getTimestamp("dispatched_at").toInstant(),
                                daysInTransit = rs.getLong("days_in_transit"),
                                dispatchedBy = rs.getLong("dispatched_by").takeIf { !rs.wasNull() },
                                dispatchedByName = rs.getString("dispatched_by_name"),
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun <T> inTransaction(work: TransferWork.() -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = JdbcTransferWork(connection, writer).work()
            connection.commit()
            result
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }

    private fun readHeader(connection: Connection, id: Long): TransferHeader? =
        connection.prepareStatement("$HEADER_SELECT WHERE t.id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toHeader() else null }
        }

    private fun readViews(connection: Connection, transferId: Long): List<TransferLineView> =
        readRawLines(connection, transferId).map {
            TransferLineView(
                id = it.id,
                productId = it.productId,
                productCode = it.productCode,
                productName = it.productName,
                quantity = it.quantity,
                fromLocationId = it.fromLocationId,
                fromLocationDisplay = it.fromDisplay,
                note = it.note,
            )
        }

    private companion object {
        /**
         * La cabecera con sus totales derivados de las líneas. `lineCount` y `totalUnits` se
         * cuentan en la base: el listado no trae las líneas, así que no hay nada que contar en memoria.
         */
        const val HEADER_SELECT = """
            SELECT t.id, t.code, t.status, t.note,
                   t.from_warehouse_id, wo.code AS from_code,
                   t.to_warehouse_id, wd.code AS to_code,
                   t.dispatched_by, ud.name AS dispatched_by_name, t.dispatched_at,
                   t.received_by, ur.name AS received_by_name, t.received_at,
                   COALESCE(l.line_count, 0) AS line_count,
                   COALESCE(l.total_units, 0) AS total_units
            FROM transfers t
            JOIN warehouses wo ON wo.id = t.from_warehouse_id
            JOIN warehouses wd ON wd.id = t.to_warehouse_id
            LEFT JOIN users ud ON ud.id = t.dispatched_by
            LEFT JOIN users ur ON ur.id = t.received_by
            LEFT JOIN (
                SELECT transfer_id, count(*) AS line_count, SUM(quantity) AS total_units
                FROM transfer_lines GROUP BY transfer_id
            ) l ON l.transfer_id = t.id
        """
    }
}

/** Una línea leída de la base, con su origen ya formateado. */
private data class RawLine(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val quantity: BigDecimal,
    val fromLocationId: Long?,
    val fromDisplay: String?,
    val note: String?,
)

private fun ResultSet.toHeader(): TransferHeader = TransferHeader(
    id = getLong("id"),
    code = getString("code"),
    fromWarehouseId = getLong("from_warehouse_id"),
    fromWarehouseCode = getString("from_code"),
    toWarehouseId = getLong("to_warehouse_id"),
    toWarehouseCode = getString("to_code"),
    status = getString("status"),
    lineCount = getInt("line_count"),
    totalUnits = getBigDecimal("total_units"),
    note = getString("note"),
    dispatchedBy = getLong("dispatched_by").takeIf { !wasNull() },
    dispatchedByName = getString("dispatched_by_name"),
    dispatchedAt = getTimestamp("dispatched_at")?.toInstant(),
    receivedBy = getLong("received_by").takeIf { !wasNull() },
    receivedByName = getString("received_by_name"),
    receivedAt = getTimestamp("received_at")?.toInstant(),
)

/**
 * Las operaciones de [TransferWork] sobre una conexión. Vive y muere con la transacción que la
 * crea: no guarda nada entre llamadas.
 */
private class JdbcTransferWork(
    private val connection: Connection,
    private val writer: AuditWriter,
) : TransferWork {

    override fun status(id: Long): TransferStatus? =
        connection.prepareStatement("SELECT status FROM transfers WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> if (rs.next()) TransferStatus.valueOf(rs.getString(1)) else null }
        }

    override fun route(id: Long): TransferRoute =
        connection.prepareStatement("SELECT from_warehouse_id, to_warehouse_id FROM transfers WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                rs.next()
                TransferRoute(rs.getLong(1), rs.getLong(2))
            }
        }

    override fun lines(id: Long): List<TransferLine> = readRawLines(connection, id).map {
        TransferLine(
            id = it.id,
            productId = it.productId,
            productCode = it.productCode,
            productName = it.productName,
            quantity = Quantity.ofScaled(it.quantity.movePointRight(3).toLong()),
            fromLocationId = it.fromLocationId,
        )
    }

    override fun activeProduct(productCode: String): ActiveProduct? =
        connection.prepareStatement("SELECT id, name FROM products WHERE product_code = ? AND active").use { st ->
            st.setString(1, productCode)
            st.executeQuery().use { rs -> if (rs.next()) ActiveProduct(rs.getLong("id"), rs.getString("name")) else null }
        }

    override fun receivingLocation(warehouseId: Long): Long? =
        connection.prepareStatement(
            "SELECT id FROM locations WHERE warehouse_id = ? AND kind = 'RECEIVING' ORDER BY id LIMIT 1",
        ).use { st ->
            st.setLong(1, warehouseId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    /**
     * El siguiente código: `TR-AAAA-NNNN`. Se cuenta dentro de la transacción; la unicidad la
     * garantiza el índice único de `code`, que es lo que evita la carrera.
     */
    override fun nextCode(): String {
        val year = LocalDate.now().year
        val nextNumber = connection.prepareStatement("SELECT count(*) + 1 FROM transfers WHERE code LIKE ?").use { st ->
            st.setString(1, "TR-$year-%")
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        return "TR-$year-" + nextNumber.toString().padStart(4, '0')
    }

    override fun insertDraft(
        code: String,
        route: TransferRoute,
        note: String?,
        actorId: Long?,
        lines: List<TransferLine>,
    ): Long {
        val id = connection.prepareStatement(
            "INSERT INTO transfers (code, from_warehouse_id, to_warehouse_id, status, note, created_by) " +
                "VALUES (?, ?, ?, 'DRAFT', ?, ?) RETURNING id",
        ).use { st ->
            st.setString(1, code)
            st.setLong(2, route.fromWarehouseId)
            st.setLong(3, route.toWarehouseId)
            st.setString(4, note)
            st.setNullableLong(5, actorId)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

        for (line in lines) {
            connection.prepareStatement(
                "INSERT INTO transfer_lines (transfer_id, product_id, from_location_id, quantity, note) " +
                    "VALUES (?, ?, ?, ?::numeric, ?)",
            ).use { st ->
                st.setLong(1, id)
                st.setLong(2, line.productId)
                st.setNullableLong(3, line.fromLocationId)
                st.setBigDecimal(4, BigDecimal.valueOf(line.quantity.value).movePointLeft(3))
                st.setString(5, line.note)
                st.executeUpdate()
            }
        }
        return id
    }

    /**
     * Las dos mitades del despacho de una línea, en la MISMA conexión:
     * 1. `registrar_movimiento` con `TRANSFER_OUT` resta del origen.
     * 2. Se inserta la fila de tránsito.
     * Después se recalcula el total: el resultado es el mismo número que antes de despachar.
     */
    override fun moveOut(transferId: Long, route: TransferRoute, line: TransferLine, actorId: Long?) {
        val units = BigDecimal.valueOf(line.quantity.value).movePointLeft(3)
        recordMovement(line.productId, route.fromWarehouseId, "TRANSFER_OUT", units, line.fromLocationId, null, transferId, actorId)

        connection.prepareStatement(
            "INSERT INTO stock_in_transit " +
                "(product_id, from_warehouse_id, to_warehouse_id, from_location_id, quantity, transfer_id) " +
                "VALUES (?, ?, ?, ?, ?::numeric, ?)",
        ).use { st ->
            st.setLong(1, line.productId)
            st.setLong(2, route.fromWarehouseId)
            st.setLong(3, route.toWarehouseId)
            st.setNullableLong(4, line.fromLocationId)
            st.setBigDecimal(5, units)
            st.setLong(6, transferId)
            st.executeUpdate()
        }

        recalcularTotal(line.productId)
    }

    override fun openTransit(transferId: Long): List<OpenTransit> =
        connection.prepareStatement(
            "SELECT id, product_id, quantity, from_location_id FROM stock_in_transit " +
                "WHERE transfer_id = ? AND received_at IS NULL ORDER BY id",
        ).use { st ->
            st.setLong(1, transferId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            OpenTransit(
                                transitId = rs.getLong(1),
                                productId = rs.getLong(2),
                                quantity = rs.getBigDecimal(3),
                                fromLocationId = rs.getLong(4).takeIf { !rs.wasNull() },
                            ),
                        )
                    }
                }
            }
        }

    /**
     * Mueve la mercancía de tránsito a [toLocation] y cierra la fila. Primero se suma y después se
     * cierra: el total no cambia porque el tránsito deja de contar en el mismo paso en que la
     * mercancía entra en la ubicación.
     */
    override fun land(transit: OpenTransit, transferId: Long, warehouseId: Long, toLocation: Long?, actorId: Long?) {
        // Sin origen: la mercancía en tránsito no está en ninguna ubicación. Pasarle el destino como
        // origen haría `0 - n` y la base lo rechazaría con `stock_non_negative`.
        recordMovement(transit.productId, warehouseId, "TRANSFER_IN", transit.quantity, null, toLocation, transferId, actorId)

        connection.prepareStatement("UPDATE stock_in_transit SET received_at = now() WHERE id = ?").use { st ->
            st.setLong(1, transit.transitId)
            st.executeUpdate()
        }

        recalcularTotal(transit.productId)
    }

    override fun markDispatched(id: Long, actorId: Long?) {
        connection.prepareStatement(
            "UPDATE transfers SET status = 'DISPATCHED', dispatched_by = ?, dispatched_at = now(), " +
                "updated_at = now() WHERE id = ?",
        ).use { st ->
            st.setNullableLong(1, actorId)
            st.setLong(2, id)
            st.executeUpdate()
        }
    }

    override fun markReceived(id: Long, actorId: Long?) {
        connection.prepareStatement(
            "UPDATE transfers SET status = 'RECEIVED', received_by = ?, received_at = now(), " +
                "updated_at = now() WHERE id = ?",
        ).use { st ->
            st.setNullableLong(1, actorId)
            st.setLong(2, id)
            st.executeUpdate()
        }
    }

    override fun markCancelled(id: Long) {
        connection.prepareStatement("UPDATE transfers SET status = 'CANCELLED', updated_at = now() WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeUpdate()
        }
    }

    /**
     * Registra la transición en la bitácora, dentro de esta conexión: si la operación se revierte,
     * el registro también. Sin usuario no hay acción humana que atribuir: la base exige actor para
     * los canales humanos, así que se registra como SYSTEM.
     */
    override fun audit(id: Long, actorId: Long?, action: String, previous: TransferStatus?, next: TransferStatus) {
        writer.record(
            connection = connection,
            actorUserId = actorId,
            actorUsername = null,
            channel = if (actorId != null) AuditWriter.Channel.WEB else AuditWriter.Channel.SYSTEM,
            entityType = "transfer",
            entityId = id.toString(),
            action = action,
            level = AuditWriter.Level.RELEVANT,
            previousState = previous?.let { """{"status":"${it.name}"}""" },
            newState = """{"status":"${next.name}"}""",
        )
    }

    /**
     * Llama a `registrar_movimiento`, la MISMA función que usa el resto del sistema: duplicar su
     * lógica aquí sería tener dos verdades sobre cómo se mueve el inventario.
     */
    private fun recordMovement(
        productId: Long,
        warehouseId: Long,
        moveType: String,
        units: BigDecimal,
        fromLocation: Long?,
        toLocation: Long?,
        transferId: Long,
        actorId: Long?,
    ) {
        connection.prepareStatement(
            """
            SELECT registrar_movimiento(
                ?, ?, ?::movement_type, ?::numeric, ?, ?, NULL, NULL, NULL, NULL, ?, NULL, ?
            )
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, productId)
            st.setLong(2, warehouseId)
            st.setString(3, moveType)
            st.setBigDecimal(4, units)
            st.setNullableLong(5, fromLocation)
            st.setNullableLong(6, toLocation)
            st.setNullableLong(7, actorId)
            st.setString(8, "Traslado #$transferId")
            st.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun recalcularTotal(productId: Long) {
        connection.prepareStatement("SELECT recalcular_total_producto(?)").use { st ->
            st.setLong(1, productId)
            st.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value != null) setLong(index, value) else setNull(index, Types.BIGINT)
    }
}


private fun readRawLines(connection: Connection, transferId: Long): List<RawLine> = connection.prepareStatement(
    """
    SELECT tl.id, tl.product_id, p.product_code, p.name AS product_name,
           tl.quantity, tl.from_location_id, tl.note,
           CASE l.kind
               WHEN 'RECEIVING'  THEN 'RECEPCION'
               WHEN 'UNASSIGNED' THEN 'SIN-UBICAR'
               ELSE l.hall || '-' || l.code || '-' || l."position"
           END AS from_display
    FROM transfer_lines tl
    JOIN products p ON p.id = tl.product_id
    LEFT JOIN locations l ON l.id = tl.from_location_id
    WHERE tl.transfer_id = ?
    ORDER BY tl.id
    """.trimIndent(),
).use { st ->
    st.setLong(1, transferId)
    st.executeQuery().use { rs ->
        buildList {
            while (rs.next()) {
                add(
                    RawLine(
                        id = rs.getLong("id"),
                        productId = rs.getLong("product_id"),
                        productCode = rs.getString("product_code"),
                        productName = rs.getString("product_name"),
                        quantity = rs.getBigDecimal("quantity"),
                        fromLocationId = rs.getLong("from_location_id").takeIf { !rs.wasNull() },
                        fromDisplay = rs.getString("from_display"),
                        note = rs.getString("note"),
                    ),
                )
            }
        }
    }
}

