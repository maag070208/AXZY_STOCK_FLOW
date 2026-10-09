package dev.axzy.stock.backend.transfer

import dev.axzy.stock.backend.audit.AuditWriter
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.domain.transfer.Transfer
import dev.axzy.stock.domain.transfer.TransferLine
import dev.axzy.stock.domain.transfer.TransferStatus
import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TRASLADOS ENTRE BODEGAS (contrato §7)
 *
 * ── LA INVARIANTE, Y CÓMO SE SOSTIENE AQUÍ ─────────────────────────────────
 *
 * > `Σ stock_by_location + Σ stock_in_transit` **se conserva en cada transición**.
 *
 * La mercancía en el camión **sigue contando** en el inventario. Cada operación
 * se ejecuta en **una sola transacción** y hace las dos mitades del movimiento:
 *
 * | Acción | Ubicaciones | Tránsito | Total |
 * |---|---|---|---|
 * | **Despachar** | −Q en origen | **+Q** (fila nueva, abierta) | **igual** |
 * | **Recibir** | +Q en destino | la fila se cierra (`received_at`) | **igual** |
 * | **Cancelar** desde `DRAFT` | sin tocar | sin tocar | igual |
 *
 * **Si las dos mitades no van en la misma transacción, el inventario se
 * descuadra** y el descuadre aparece entre dos reportes. Es la misma razón por la
 * que el motor de inventario vive en PL/pgSQL (V6).
 *
 * ── LO QUE EL MOTOR NO HACE, Y POR ESO ESTÁ AQUÍ ───────────────────────────
 *
 * `registrar_movimiento` **no crea la fila de `stock_in_transit`**: mueve
 * ubicaciones, recalcula el total y escribe el kardex. Se comprobó leyendo V6 —
 * `stock_in_transit` solo aparece en `recalcular_total_producto` y en
 * `comprobar_proyecciones`, nunca en una escritura. Así que el tránsito lo
 * mantiene este servicio, **dentro de la misma conexión y la misma transacción**
 * que el movimiento.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class TransferService(
    private val dataSource: DataSource,
    private val audit: AuditWriter = AuditWriter(dataSource),
) {

    /** Un traslado con su detalle, o `null` si no existe. */
    fun byId(id: Long): TransferDetailResponse? = dataSource.connection.use { connection ->
        val cabecera = readHeader(connection, id) ?: return@use null
        val linesRead = readLines(connection, id)
        TransferDetailResponse(cabecera, linesRead)
    }

    /**
     * Lista traslados, opcionalmente filtrados por estado.
     *
     * Devuelve **solo la cabecera**, como el listado de órdenes: el detalle trae
     * las líneas porque la pantalla de despacho no puede funcionar sin ellas, pero
     * un listado con las líneas de cada traslado sería un N+1 disfrazado.
     */
    fun list(
        status: String?,
        warehouseId: Long?,
        offset: Int,
        limit: Int,
    ): List<TransferResponse> = dataSource.connection.use { connection ->
        val sql = buildString {
            append(HEADER_SELECT)
            append(" WHERE 1=1 ")
            if (status != null) append(" AND t.status = ?::transfer_status ")
            if (warehouseId != null) {
                append(" AND (t.from_warehouse_id = ? OR t.to_warehouse_id = ?) ")
            }
            append(" ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?")
        }

        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (status != null) st.setString(i++, status)
            if (warehouseId != null) {
                st.setLong(i++, warehouseId)
                st.setLong(i, warehouseId)
                i++
            }
            st.setInt(i++, limit)
            st.setInt(i, offset)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toTransferResponse()) }
            }
        }
    }

    fun count(status: String?, warehouseId: Long?): Long = dataSource.connection.use { connection ->
        val sql = buildString {
            append("SELECT count(*) FROM transfers t WHERE 1=1 ")
            if (status != null) append(" AND t.status = ?::transfer_status ")
            if (warehouseId != null) append(" AND (t.from_warehouse_id = ? OR t.to_warehouse_id = ?) ")
        }
        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (status != null) st.setString(i++, status)
            if (warehouseId != null) {
                st.setLong(i++, warehouseId)
                st.setLong(i, warehouseId)
            }
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    /**
     * Los traslados despachados y NO recibidos, con sus días transcurridos.
     *
     * **Un traslado nunca recibido es una deuda visible, no un dato que se
     * pierde.** Por eso esta consulta existe y por eso el orden es por antigüedad
     * descendente: el que lleva más días en el camino es el que hay que ir a
     * buscar.
     */
    fun inTransit(): List<InTransitTransferResponse> = dataSource.connection.use { connection ->
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
                            InTransitTransferResponse(
                                id = rs.getLong("id"),
                                code = rs.getString("code"),
                                fromWarehouseCode = rs.getString("from_code"),
                                toWarehouseCode = rs.getString("to_code"),
                                status = rs.getString("status"),
                                lineCount = rs.getInt("line_count"),
                                totalUnits = rs.getBigDecimal("total_units").setScale(3).toPlainString(),
                                dispatchedAt = rs.getTimestamp("dispatched_at")?.toInstant().toString(),
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

    // ═════════════════════════════════════════════════════════════════════════
    // ESCRITURA
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Crea un traslado en `DRAFT`, con sus líneas.
     *
     * No mueve stock: un borrador es una intención. La mercancía sale de las
     * ubicaciones al **despachar**, que es un acto con responsable.
     */
    fun create(request: TransferCreateRequest, userId: Long?): TransferDetailResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val requestedLines = request.lines.map { it.toDomainLine(connection) }

                // Las reglas del DOMINIO, no una copia en la ruta. `SameWarehouse`
                // y `EmptyTransfer` se comprueban antes de tocar la base para poder
                // dar un 422 que señale el campo, en vez de traducir una violación
                // de restricción que no dice qué está mal.
                when (val valido = Transfer.validate(request.fromWarehouseId, request.toWarehouseId, requestedLines)) {
                    else -> valido.errorOrNull()?.let { throw it.toUnprocessable() }
                }

                val code = nextCode(connection)

                val id = connection.prepareStatement(
                    "INSERT INTO transfers (code, from_warehouse_id, to_warehouse_id, status, note, created_by) " +
                        "VALUES (?, ?, ?, 'DRAFT', ?, ?) RETURNING id",
                ).use { st ->
                    st.setString(1, code)
                    st.setLong(2, request.fromWarehouseId)
                    st.setLong(3, request.toWarehouseId)
                    st.setString(4, request.note?.trim()?.takeIf { it.isNotEmpty() })
                    if (userId != null) st.setLong(5, userId) else st.setNull(5, java.sql.Types.BIGINT)
                    st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }

                requestedLines.forEach { line ->
                    connection.prepareStatement(
                        "INSERT INTO transfer_lines (transfer_id, product_id, from_location_id, quantity, note) " +
                            "VALUES (?, ?, ?, ?::numeric, ?)",
                    ).use { st ->
                        st.setLong(1, id)
                        st.setLong(2, line.productId)
                        // `fromLocationId` es una propiedad pública de OTRO módulo
                        // (`stock-domain`), así que Kotlin no puede hacer smart cast
                        // sobre ella: se copia a una local. Es una consecuencia de
                        // tener el dominio en un módulo aparte, y es el precio
                        // correcto por poder compartirlo con el móvil.
                        val origin = line.fromLocationId
                        if (origin != null) st.setLong(3, origin) else st.setNull(3, java.sql.Types.BIGINT)
                        st.setBigDecimal(4, BigDecimal.valueOf(line.quantity.value).movePointLeft(3))
                        st.setString(5, line.note)
                        st.executeUpdate()
                    }
                }

                auditTransition(connection, userId, id, "transfer.create", previous = null, next = TransferStatus.DRAFT)

                connection.commit()
                byId(id)!!
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    /**
     * Despacha el traslado: **la mercancía sale de las ubicaciones y entra en
     * tránsito**, en una sola transacción.
     *
     * Las dos escrituras van juntas porque son las dos mitades del mismo hecho.
     * Si se hicieran por separado —restar aquí, sumar el tránsito allá— un fallo
     * entre las dos dejaría la mercancía desaparecida, y el total bajaría. **Es
     * exactamente el descuadre que la invariante del contrato prohíbe.**
     */
    fun dispatch(id: Long, userId: Long?): TransferDetailResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val currentStatus = readStatus(connection, id)
                    ?: throw NotFoundException("El traslado $id no existe.", "TRANSFER_NOT_FOUND")

                // La transición la decide el DOMINIO: aquí no hay un `if` sobre
                // estados en español ni una lista de los permitidos.
                val transfer = Transfer(
                    id = id,
                    code = "",
                    fromWarehouseId = 0,
                    toWarehouseId = 0,
                    status = currentStatus,
                    lines = readLines(connection, id).map { it.toDomain() },
                )
                transfer.dispatch(userId ?: 0L).errorOrNull()?.let { throw it.toUnprocessable() }

                // El traslado de verdad, para saber de qué bodega a cuál.
                val (fromWarehouse, toWarehouse) = connection.prepareStatement(
                    "SELECT from_warehouse_id, to_warehouse_id FROM transfers WHERE id = ?",
                ).use { st ->
                    st.setLong(1, id)
                    st.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1) to rs.getLong(2)
                    }
                }

                val linesRead = readRawLines(connection, id)
                for (line in linesRead) {
                    dispatchLine(connection, id, line, fromWarehouse, toWarehouse, userId)
                }

                auditTransition(connection, userId, id, "transfer.dispatch", previous = currentStatus, next = TransferStatus.DISPATCHED)

                connection.prepareStatement(
                    "UPDATE transfers SET status = 'DISPATCHED', dispatched_by = ?, dispatched_at = now(), " +
                        "updated_at = now() WHERE id = ?",
                ).use { st ->
                    if (userId != null) st.setLong(1, userId) else st.setNull(1, java.sql.Types.BIGINT)
                    st.setLong(2, id)
                    st.executeUpdate()
                }

                connection.commit()
                byId(id)!!
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    /**
     * Recibe el traslado: la mercancía **llega a las ubicaciones de destino** y su
     * fila de tránsito se cierra.
     *
     * El orden importa: primero se suma en destino, después se cierra el tránsito.
     * Al revés —cerrar y luego sumar— un fallo entre las dos dejaría la mercancía
     * fuera de las dos sumas y **el total bajaría**.
     */
    fun receive(id: Long, userId: Long?): TransferDetailResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val currentStatus = readStatus(connection, id)
                    ?: throw NotFoundException("El traslado $id no existe.", "TRANSFER_NOT_FOUND")

                val transfer = Transfer(
                    id = id,
                    code = "",
                    fromWarehouseId = 0,
                    toWarehouseId = 0,
                    status = currentStatus,
                    lines = readLines(connection, id).map { it.toDomain() },
                )
                transfer.receive(userId ?: 0L).errorOrNull()?.let { throw it.toUnprocessable() }

                val (_, toWarehouse) = connection.prepareStatement(
                    "SELECT from_warehouse_id, to_warehouse_id FROM transfers WHERE id = ?",
                ).use { st ->
                    st.setLong(1, id)
                    st.executeQuery().use { rs -> rs.next(); rs.getLong(1) to rs.getLong(2) }
                }

                // La ubicación de destino: la de recepción de la bodega de destino.
                // Es donde llega la mercancía y desde donde se ubica después, que es
                // justo el flujo de recepción que ya existe.
                val destino = receivingLocation(connection, toWarehouse)
                    ?: throw UnprocessableException(
                        code = "NO_RECEIVING_LOCATION",
                        message = "La bodega de destino no tiene ubicación de recepción.",
                        details = mapOf("toWarehouseId" to toWarehouse.toString()),
                    )

                val openInTransit = connection.prepareStatement(
                    "SELECT id, product_id, quantity FROM stock_in_transit " +
                        "WHERE transfer_id = ? AND received_at IS NULL ORDER BY id",
                ).use { st ->
                    st.setLong(1, id)
                    st.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(Triple(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3)))
                            }
                        }
                    }
                }

                for ((transitId, productId, transitQuantity) in openInTransit) {
                    // 1. Suma en destino. **Sin origen, y es lo correcto.**
                    //
                    // `registrar_movimiento` RESTA de `fromLocation` y SUMA en
                    // `toLocation`. Pasarle el destino como origen hacía
                    // `0 - 100 = -100` y la base lo rechazaba con
                    // `stock_non_negative`: **el traslado no se podía recibir**.
                    //
                    // La mercancía en tránsito **no está en ninguna ubicación** —esa
                    // es toda la razón de `stock_in_transit`—, así que aquí solo hay
                    // que sumar. El total no se mueve porque el tránsito ya contaba y
                    // deja de contar al cerrarse en el paso 2.
                    recordMovement(
                        connection = connection,
                        productId = productId,
                        warehouseId = toWarehouse,
                        moveType = "TRANSFER_IN",
                        units = transitQuantity,
                        fromLocation = null,
                        toLocation = destino,
                        transferId = id,
                        userId = userId,
                    )

                    // 2. Se cierra el tránsito. Ahora el total baja por la resta
                    //    implícita y sube por lo que se acaba de sumar: igual.
                    connection.prepareStatement(
                        "UPDATE stock_in_transit SET received_at = now() WHERE id = ?",
                    ).use { st ->
                        st.setLong(1, transitId)
                        st.executeUpdate()
                    }

                    recalcularTotal(connection, productId)
                }

                // Si no había tránsito abierto (despacho cancelado a medias), se
                // recibe igual: el estado del traslado es lo que manda.
                val linesRead = readRawLines(connection, id)
                if (openInTransit.isEmpty() && linesRead.isNotEmpty()) {
                    throw ConflictException(
                        code = "NOTHING_IN_TRANSIT",
                        message = "El traslado $id no tiene mercancía en tránsito que recibir.",
                    )
                }

                auditTransition(connection, userId, id, "transfer.receive", previous = currentStatus, next = TransferStatus.RECEIVED)

                connection.prepareStatement(
                    "UPDATE transfers SET status = 'RECEIVED', received_by = ?, received_at = now(), " +
                        "updated_at = now() WHERE id = ?",
                ).use { st ->
                    if (userId != null) st.setLong(1, userId) else st.setNull(1, java.sql.Types.BIGINT)
                    st.setLong(2, id)
                    st.executeUpdate()
                }

                connection.commit()
                byId(id)!!
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    /**
     * Cancela el traslado.
     *
     * **Desde `DRAFT` no hay nada que deshacer.** La cancelación de un
     * `DISPATCHED` se maneja con la regla del dominio, que la permite, pero la
     * mercancía ya está en tránsito: `cancel` sobre un despachado **devuelve el
     * stock a su ubicación de origen** en la misma transacción, para no dejar
     * mercancía en un limbo que ningún reporte explica.
     */
    fun cancel(id: Long, userId: Long?): TransferDetailResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val currentStatus = readStatus(connection, id)
                    ?: throw NotFoundException("El traslado $id no existe.", "TRANSFER_NOT_FOUND")

                val transfer = Transfer(
                    id = id,
                    code = "",
                    fromWarehouseId = 0,
                    toWarehouseId = 0,
                    status = currentStatus,
                    lines = emptyList(),
                )
                transfer.cancel().errorOrNull()?.let { throw it.toUnprocessable() }

                val (fromWarehouse, _) = connection.prepareStatement(
                    "SELECT from_warehouse_id, to_warehouse_id FROM transfers WHERE id = ?",
                ).use { st ->
                    st.setLong(1, id)
                    st.executeQuery().use { rs -> rs.next(); rs.getLong(1) to rs.getLong(2) }
                }

                // Solo si estaba despachado: hay tránsito abierto que devolver.
                val openInTransit = connection.prepareStatement(
                    "SELECT id, product_id, quantity, from_location_id FROM stock_in_transit " +
                        "WHERE transfer_id = ? AND received_at IS NULL ORDER BY id",
                ).use { st ->
                    st.setLong(1, id)
                    st.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    listOf(
                                        rs.getLong(1).toString(),
                                        rs.getLong(2).toString(),
                                        rs.getBigDecimal(3).toPlainString(),
                                        rs.getObject(4)?.toString() ?: "",
                                    ),
                                )
                            }
                        }
                    }
                }

                for (row in openInTransit) {
                    val productId = row[1].toLong()
                    val transitQuantity = BigDecimal(row[2])
                    val origin = row[3].toLongOrNull()

                    // Vuelve al origen: el total NO cambia, porque sale del
                    // tránsito y entra en la ubicación.
                    recordMovement(
                        connection = connection,
                        productId = productId,
                        warehouseId = fromWarehouse,
                        moveType = "TRANSFER_IN",
                        units = transitQuantity,
                        fromLocation = null,
                        toLocation = origin,
                        transferId = id,
                        userId = userId,
                    )

                    connection.prepareStatement(
                        "UPDATE stock_in_transit SET received_at = now() WHERE id = ?",
                    ).use { st ->
                        st.setLong(1, row[0].toLong())
                        st.executeUpdate()
                    }

                    recalcularTotal(connection, productId)
                }

                auditTransition(connection, userId, id, "transfer.cancel", previous = currentStatus, next = TransferStatus.CANCELLED)

                connection.prepareStatement(
                    "UPDATE transfers SET status = 'CANCELLED', updated_at = now() WHERE id = ?",
                ).use { st ->
                    st.setLong(1, id)
                    st.executeUpdate()
                }

                connection.commit()
                byId(id)!!
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    // ── Internos ─────────────────────────────────────────────────────────────

    /**
     * Las dos mitades del despacho de una línea, en la MISMA conexión.
     *
     * 1. `registrar_movimiento` con `TRANSFER_OUT` resta del origen.
     * 2. Se inserta la fila de tránsito con la cantidad.
     *
     * Y después se recalcula el total, que vuelve a sumar: el resultado es el
     * mismo número que antes de despachar. **Esa igualdad es la invariante.**
     */
    /**
     * Registra la transición en la bitácora, **dentro de la conexión del llamador**: si la
     * operación se revierte, el registro también. Un traslado que cambia de estado sin dejar
     * rastro de quién lo hizo no es auditable.
     */
    private fun auditTransition(
        connection: Connection,
        userId: Long?,
        id: Long,
        action: String,
        previous: TransferStatus?,
        next: TransferStatus,
    ) {
        audit.record(
            connection = connection,
            actorUserId = userId,
            actorUsername = null,
            // Sin usuario no hay acción humana que atribuir: la base exige actor para
            // los canales humanos (`audit_human_has_actor`), así que se registra como SYSTEM.
            // Las rutas siempre pasan un usuario autenticado.
            channel = if (userId != null) AuditWriter.Channel.WEB else AuditWriter.Channel.SYSTEM,
            entityType = "transfer",
            entityId = id.toString(),
            action = action,
            level = AuditWriter.Level.RELEVANT,
            previousState = previous?.let { """{"status":"${it.name}"}""" },
            newState = """{"status":"${next.name}"}""",
        )
    }

    private fun dispatchLine(
        connection: Connection,
        transferId: Long,
        line: RawLine,
        fromWarehouse: Long,
        toWarehouse: Long,
        userId: Long?,
    ) {
        recordMovement(
            connection = connection,
            productId = line.productId,
            warehouseId = fromWarehouse,
            moveType = "TRANSFER_OUT",
            units = line.quantity,
            fromLocation = line.fromLocationId,
            toLocation = null,
            transferId = transferId,
            userId = userId,
        )

        connection.prepareStatement(
            "INSERT INTO stock_in_transit " +
                "(product_id, from_warehouse_id, to_warehouse_id, from_location_id, quantity, transfer_id) " +
                "VALUES (?, ?, ?, ?, ?::numeric, ?)",
        ).use { st ->
            st.setLong(1, line.productId)
            st.setLong(2, fromWarehouse)
            st.setLong(3, toWarehouse)
            if (line.fromLocationId != null) st.setLong(4, line.fromLocationId)
            else st.setNull(4, java.sql.Types.BIGINT)
            st.setBigDecimal(5, line.quantity)
            st.setLong(6, transferId)
            st.executeUpdate()
        }

        recalcularTotal(connection, line.productId)
    }

    /**
     * Llama a `registrar_movimiento`, la MISMA función que usa el resto del
     * sistema.
     *
     * No se hace `INSERT` en el kardex a mano: la función mantiene el kardex y las
     * proyecciones en la misma sentencia, y **duplicar su lógica aquí sería tener
     * dos verdades sobre cómo se mueve el inventario**.
     */
    private fun recordMovement(
        connection: Connection,
        productId: Long,
        warehouseId: Long,
        moveType: String,
        units: BigDecimal,
        fromLocation: Long?,
        toLocation: Long?,
        transferId: Long,
        userId: Long?,
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
            if (fromLocation != null) st.setLong(5, fromLocation) else st.setNull(5, java.sql.Types.BIGINT)
            if (toLocation != null) st.setLong(6, toLocation) else st.setNull(6, java.sql.Types.BIGINT)
            if (userId != null) st.setLong(7, userId) else st.setNull(7, java.sql.Types.BIGINT)
            st.setString(8, "Traslado #$transferId")
            st.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun recalcularTotal(connection: Connection, productId: Long) {
        connection.prepareStatement("SELECT recalcular_total_producto(?)").use { st ->
            st.setLong(1, productId)
            st.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun receivingLocation(connection: Connection, warehouseId: Long): Long? =
        connection.prepareStatement(
            "SELECT id FROM locations WHERE warehouse_id = ? AND kind = 'RECEIVING' ORDER BY id LIMIT 1",
        ).use { st ->
            st.setLong(1, warehouseId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    /**
     * El siguiente código de traslado: `TR-AAAA-NNNN`.
     *
     * Se cuenta sobre el año en curso con un `count`, dentro de la transacción.
     * **La unicidad la garantiza el índice único de `code`**: si dos peticiones
     * simultáneas calcularan el mismo número, una fallaría al insertar. Es
     * preferible un `409` legible a un número duplicado, y el índice es la única
     * forma de que no haya carrera.
     */
    private fun nextCode(connection: Connection): String {
        val year = java.time.LocalDate.now().year
        val nextNumber = connection.prepareStatement(
            "SELECT count(*) + 1 FROM transfers WHERE code LIKE ?",
        ).use { st ->
            st.setString(1, "TR-$year-%")
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        return "TR-$year-" + nextNumber.toString().padStart(4, '0')
    }

    private fun readStatus(connection: Connection, id: Long): TransferStatus? =
        connection.prepareStatement("SELECT status FROM transfers WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (rs.next()) TransferStatus.valueOf(rs.getString(1)) else null
            }
        }

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

    private fun readRawLines(connection: Connection, transferId: Long): List<RawLine> =
        connection.prepareStatement(
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

    private fun readLines(connection: Connection, transferId: Long): List<TransferLineResponse> =
        readRawLines(connection, transferId).map {
            TransferLineResponse(
                id = it.id,
                productId = it.productId,
                productCode = it.productCode,
                productName = it.productName,
                quantity = it.quantity.setScale(3).toPlainString(),
                fromLocationId = it.fromLocationId,
                fromLocationDisplayName = it.fromDisplay,
                note = it.note,
            )
        }

    private fun readHeader(connection: Connection, id: Long): TransferResponse? =
        connection.prepareStatement("$HEADER_SELECT WHERE t.id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toTransferResponse() else null }
        }

    private companion object {
        /**
         * La cabecera con sus totales derivados de las líneas.
         *
         * `lineCount` y `totalUnits` se **cuentan en la base** y no en Kotlin: el
         * listado no trae las líneas, así que no hay nada que contar en memoria.
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

private fun java.sql.ResultSet.toTransferResponse(): TransferResponse = TransferResponse(
    id = getLong("id"),
    code = getString("code"),
    fromWarehouseId = getLong("from_warehouse_id"),
    fromWarehouseCode = getString("from_code"),
    toWarehouseId = getLong("to_warehouse_id"),
    toWarehouseCode = getString("to_code"),
    status = getString("status"),
    lineCount = getInt("line_count"),
    totalUnits = getBigDecimal("total_units").setScale(3).toPlainString(),
    note = getString("note"),
    dispatchedBy = getLong("dispatched_by").takeIf { !wasNull() },
    dispatchedByName = getString("dispatched_by_name"),
    dispatchedAt = getTimestamp("dispatched_at")?.toInstant()?.toString(),
    receivedBy = getLong("received_by").takeIf { !wasNull() },
    receivedByName = getString("received_by_name"),
    receivedAt = getTimestamp("received_at")?.toInstant()?.toString(),
)

private fun TransferLineResponse.toDomain() = TransferLine(
    id = id,
    productId = productId,
    productCode = productCode,
    productName = productName,
    quantity = Quantity.ofScaled(BigDecimal(quantity).movePointRight(3).toLong()),
    fromLocationId = fromLocationId,
)

private fun TransferLineRequest.toDomainLine(connection: Connection): TransferLine {
    val product = connection.prepareStatement(
        "SELECT id, name FROM products WHERE product_code = ? AND active",
    ).use { st ->
        st.setString(1, productCode.trim())
        st.executeQuery().use { rs ->
            if (rs.next()) rs.getLong("id") to rs.getString("name") else null
        }
    } ?: throw UnprocessableException(
        code = "PRODUCT_NOT_FOUND",
        message = "El producto '${productCode}' no existe o está inactivo.",
        details = mapOf("productCode" to productCode),
    )

    val parsedQuantity = runCatching { BigDecimal(quantity.trim()) }.getOrElse {
        throw UnprocessableException(
            code = "INVALID_QUANTITY",
            message = "La cantidad '$quantity' no es un número.",
            details = mapOf("quantity" to quantity),
        )
    }
    if (parsedQuantity <= BigDecimal.ZERO) {
        throw UnprocessableException(
            code = "INVALID_QUANTITY",
            message = "La cantidad del traslado debe ser positiva. Se recibió '$quantity'.",
            details = mapOf("quantity" to quantity),
        )
    }

    return TransferLine(
        id = 0,
        productId = product.first,
        productCode = productCode.trim(),
        productName = product.second,
        quantity = Quantity.ofScaled(parsedQuantity.movePointRight(3).toLong()),
        fromLocationId = fromLocationId,
        note = note?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * Traduce un fallo del dominio al `422` del contrato.
 *
 * El código del dominio viaja tal cual: es un código del catálogo cerrado y el
 * cliente decide con él.
 */
private fun dev.axzy.stock.domain.error.DomainError.toUnprocessable() = UnprocessableException(
    code = code,
    message = message,
    details = emptyMap(),
)
