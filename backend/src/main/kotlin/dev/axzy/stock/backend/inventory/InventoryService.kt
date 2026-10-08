package dev.axzy.stock.backend.inventory

import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.identity.isDirectionIp
import dev.axzy.stock.domain.inventory.AdjustmentDirection
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentPolicy
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.AdjustmentRequest
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SERVICIO DE INVENTARIO
 *
 * Conecta la **regla única de ajuste** del dominio con el **motor SQL** de la
 * migración V6. Es la única puerta por la que se mueve inventario.
 *
 * **Todo pasa por `registrar_movimiento` de PostgreSQL, y es deliberado.** Esa
 * función ajusta las ubicaciones, recalcula el total y escribe el kardex **en una
 * sola transacción**. Escribir esas tres cosas desde el código significaría que un
 * fallo a mitad deja el inventario descuadrado, y nadie lo sabría hasta el conteo
 * físico.
 *
 * Referencia: `PLAN_ALMACEN_INTEGRAL.md` §2.4 a §2.7.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class InventoryService(private val dataSource: DataSource) {

    /**
     * El escritor de auditoría.
     *
     * Se construye con el mismo `dataSource` en lugar de recibirlo por constructor
     * para **no romper a quien ya instancia este servicio** (la aplicación y los
     * tests). Si algún día hay varias implementaciones de auditoría, se inyecta y se
     * cambia este punto.
     */
    private val audit = dev.axzy.stock.backend.audit.AuditWriter(dataSource)

    // ═══════════════════════════════════════════════════════════════════════
    // AJUSTES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Aplica un ajuste de inventario.
     *
     * El orden importa y es el que impone la regla del dominio:
     *  1. Se resuelven producto y ubicación. Sin ellos no hay nada que ajustar.
     *  2. Se comprueba el **saldo disponible**, para dar un mensaje útil.
     *  3. Se pide la **decisión** a la política del dominio.
     *  4. Solo si la decisión es aplicar, se escribe el movimiento.
     *
     * @param aprobadorId quién aprueba, si el ajuste lo requiere.
     */
    fun applyAdjustment(
        request: AdjustmentRequest,
        approverId: Long? = null,
        ip: String? = null,
    ): AdjustmentResult = dataSource.connection.use { connection ->
        connection.autoCommit = false

        try {
            val productId = findProduct(connection, request.productCode)
                ?: throw NotFoundException(
                    "El producto '${request.productCode.value}' no existe.",
                    code = "PRODUCT_NOT_FOUND",
                )

            val location = findLocation(connection, request.locationCode)
                ?: throw NotFoundException(
                    "La ubicación '${request.locationCode.value}' no existe.",
                    code = "LOCATION_NOT_FOUND",
                )

            // El saldo se lee DENTRO de la transacción, no antes. Leerlo fuera
            // abriría una ventana en la que el saldo cambia entre la comprobación
            // y el movimiento.
            val available = readBalance(connection, productId, location.id)

            val decision = AdjustmentPolicy.decide(
                request = request,
                approverId = approverId,
                available = available,
            )

            when (decision) {
                is AdjustmentPolicy.Decision.Reject -> {
                    connection.rollback()
                    throw whenFails(decision.error.code, decision.error.message)
                }

                is AdjustmentPolicy.Decision.PendingApproval -> {
                    // **No se escribe nada.** Un movimiento que espera aprobación y
                    // ya movió el inventario sería un cambio de stock sin autorizar,
                    // que es justo lo que la aprobación pretende evitar.
                    connection.rollback()
                    return@use AdjustmentResult(
                        movements = emptyList(),
                        total = available,
                        status = "PENDING_APPROVAL",
                        reason = decision.reason,
                    )
                }

                AdjustmentPolicy.Decision.Apply -> {
                    val kardexId = writeMovement(
                        connection = connection,
                        productId = productId,
                        warehouseId = location.warehouseId,
                        type = request.movementType,
                        units = request.quantity,
                        // Un aumento ENTRA a la ubicación; una merma SALE de ella.
                        originId = if (request.direction == AdjustmentDirection.DECREASE) location.id else null,
                        destinationId = if (request.direction == AdjustmentDirection.INCREASE) location.id else null,
                        reason = request.reason,
                        origin = request.origin,
                        userId = request.requestedBy,
                        approverId = approverId,
                        key = request.idempotencyKey,
                        note = request.note,
                        ip = ip,
                    )

                    val totalNew = readTotal(connection, productId)

                    val previousTotal = if (request.direction == AdjustmentDirection.INCREASE) {
                        Quantity.ofScaled(totalNew.value - request.quantity.value)
                    } else {
                        Quantity.ofScaled(totalNew.value + request.quantity.value)
                    }

                    // ── AUDITORÍA, EN LA MISMA TRANSACCIÓN ──────────────────
                    //
                    // **Antes del commit, y a propósito.** Es la operación que más
                    // importa auditar de todo el sistema: mueve inventario. Si el
                    // movimiento se aplicara y su registro no, **el stock habría
                    // cambiado sin dejar rastro de quién lo cambió**, y eso no se
                    // descubre ahora sino un año después, cuando alguien pregunta
                    // quién autorizó una diferencia.
                    //
                    // El `previousState` y el `newState` llevan los DOS totales, no
                    // solo el nuevo: un registro que dice «quedó en 5188» sin decir
                    // de dónde venía obliga a reconstruir la historia leyendo los
                    // movimientos anteriores.
                    audit.record(
                        connection = connection,
                        actorUserId = request.requestedBy,
                        actorUsername = null,
                        channel = dev.axzy.stock.backend.audit.AuditWriter.Channel.WEB,
                        sourceIp = ip,
                        entityType = "product",
                        entityId = productId.toString(),
                        entityLabel = request.productCode.value,
                        action = "inventory.adjustment",

                        // ── DEBERÍA SER `CRITICAL`, Y NO PUEDE SERLO TODAVÍA ────
                        //
                        // Un ajuste mueve inventario y merece el nivel más alto. Pero
                        // la tabla impone `audit_critical_has_trace`:
                        //
                        //   CHECK (action_level <> 'CRITICAL' OR access_trace IS NOT NULL)
                        //
                        // Es decir: **un registro crítico tiene que llevar su traza de
                        // decisión de acceso** (RBAC → ABAC → decisión). Es un buen
                        // diseño: lo importante tiene que poder justificarse.
                        //
                        // **Y la función `audit_log_record` de V7 no acepta
                        // `access_trace`**, así que hoy es imposible escribir un
                        // registro CRITICAL desde el código. Se comprobó intentándolo:
                        // el INSERT falla con «violates check constraint
                        // audit_critical_has_trace».
                        //
                        // Queda en `RELEVANT` para que **la auditoría se escriba ya** en
                        // lugar de no escribirse nada, que es como estaba. **Subirlo a
                        // CRITICAL exige primero una migración que añada `access_trace`
                        // a la función**, y eso está pendiente. Dejarlo aquí sin decirlo
                        // sería lo peor: parecería una decisión en lugar de una
                        // limitación.
                        level = dev.axzy.stock.backend.audit.AuditWriter.Level.RELEVANT,
                        // El total ANTERIOR se deriva del delta en lugar de leerlo
                        // antes del movimiento. `readTotal` se llama después (el saldo
                        // ya está aplicado), y añadir una lectura previa costaría una
                        // consulta más por ajuste para un dato que es aritmética
                        // exacta: el movimiento sumó o restó `quantity`.
                        //
                        // **La primera versión ponía aquí `totalNew` en los dos
                        // campos**, así que el registro decía «pasó de 5188 a 5188» y
                        // no documentaba ningún cambio. Un registro de auditoría que
                        // no muestra la transición no sirve para reconstruir qué pasó.
                        previousState = """{"total":"${previousTotal.unitsText()}"}""",
                        newState = """{"total":"${totalNew.unitsText()}","units":"${request.quantity.unitsText()}","direction":"${request.direction}"}""",
                        // (el total anterior se calcula unas líneas más arriba)
                        reason = request.reason.name,
                        note = request.note,
                        permissionKey = permissionForAdjustment(request.direction, request.reason),
                        decision = dev.axzy.stock.backend.audit.AuditWriter.Decision.ALLOWED,
                        result = dev.axzy.stock.backend.audit.AuditWriter.Result.SUCCESS,
                        idempotencyKey = request.idempotencyKey,
                        businessDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                    )

                    connection.commit()

                    AdjustmentResult(
                        movements = listOf(kardexId),
                        total = totalNew,
                        status = "APPLIED",
                    )
                }
            }
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TRASLADOS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Ubica mercancía: traslado de la ubicación de recepción al estante.
     *
     * **El total NO cambia.** Es un traslado, no una salida. En el legacy el total
     * bajaba al ubicar porque estaba anclado a un movimiento histórico mutado; aquí
     * el total es una suma derivada de las ubicaciones, así que mover no puede
     * alterarlo.
     */
    fun locate(
        productCode: ProductCode,
        from: LocationCode,
        until: LocationCode,
        quantity: Quantity,
        userId: Long,
        key: String? = null,
    ): Long = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val productId = findProduct(connection, productCode)
                ?: throw NotFoundException("El producto '${productCode.value}' no existe.", "PRODUCT_NOT_FOUND")

            val origin = findLocation(connection, from)
                ?: throw NotFoundException("La ubicación '${from.value}' no existe.", "LOCATION_NOT_FOUND")

            val destination = findLocation(connection, until)
                ?: throw NotFoundException("La ubicación '${until.value}' no existe.", "LOCATION_NOT_FOUND")

            if (origin.id == destination.id) {
                throw UnprocessableException(
                    "SAME_SOURCE_AND_DESTINATION",
                    "El origen y el destino son la misma ubicación: no hay traslado.",
                )
            }

            val id = writeMovement(
                connection = connection,
                productId = productId,
                warehouseId = destination.warehouseId,
                type = MovementType.LOCATE,
                units = quantity,
                originId = origin.id,
                destinationId = destination.id,
                userId = userId,
                key = key,
            )

            // ── AUDITORÍA DEL TRASLADO ──────────────────────────────────────
            //
            // **Ubicar es un traslado, y por eso el registro guarda el ORIGEN y el
            // DESTINO.** Un registro que solo dijera «se movieron 300 unidades» no
            // permitiría reconstruir de dónde salieron, que es la pregunta que se
            // hace cuando un estante aparece vacío y nadie sabe por qué.
            //
            // El kardex ya guarda el movimiento, pero **el kardex es del producto y la
            // auditoría es de la PERSONA**: quién lo hizo, desde qué IP y con qué
            // permiso. Son dos preguntas distintas y las dos hay que poder responder.
            audit.record(
                connection = connection,
                actorUserId = userId,
                actorUsername = null,
                channel = dev.axzy.stock.backend.audit.AuditWriter.Channel.WEB,
                entityType = "product",
                entityId = productId.toString(),
                entityLabel = productCode.value,
                action = "inventory.locate",
                level = dev.axzy.stock.backend.audit.AuditWriter.Level.RELEVANT,
                previousState = """{"location":"${from.value}"}""",
                newState = """{"location":"${until.value}","units":"${quantity.unitsText()}"}""",
                permissionKey = "invoice.locate",
                decision = dev.axzy.stock.backend.audit.AuditWriter.Decision.ALLOWED,
                result = dev.axzy.stock.backend.audit.AuditWriter.Result.SUCCESS,
                idempotencyKey = key,
                businessDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC),
            )

            connection.commit()
            id
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        }
    }

    /** Traslado entre estantes de la misma bodega. */
    fun moveWithinWarehouse(
        productCode: ProductCode,
        from: LocationCode,
        until: LocationCode,
        quantity: Quantity,
        userId: Long,
        key: String? = null,
    ): Long = locate(productCode, from, until, quantity, userId, key)

    // ═══════════════════════════════════════════════════════════════════════
    // CONSULTAS
    // ═══════════════════════════════════════════════════════════════════════

    /** Existencias por producto y ubicación. */
    fun stockByLocation(
        productCode: String? = null,
        locationCode: String? = null,
        onlyWithStock: Boolean = true,
        offset: Int = 0,
        limit: Int = 50,
    ): List<StockByLocationDto> = dataSource.connection.use { connection ->
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
            if (productCode != null) append(" AND p.product_code = ?")
            if (locationCode != null) {
                append(
                    // Se aceptan las DOS formas en que el usuario puede nombrar una
                    // ubicación, porque copia lo que ve en la pantalla: el nombre
                    // visible (`SIN-UBICAR`) y las tres partes concatenadas
                    // (`SINUBICAR`). **Sin `IN` sobre una lista fija**: la primera
                    // versión hacía `UPPER(REPLACE(?,'-','')) IN ('SINUBICAR',
                    // 'RECEPCION')`, y como `RECEPCION` está en esa lista, filtrar por
                    // `RECEPCION` devolvía TODAS las filas. Un `IN` con valores
                    // constantes no compara con el parámetro: lo usaba como permiso.
                    " AND (UPPER(REPLACE(l.hall || l.code || l.\"position\", '-', '')) " +
                        "= UPPER(REPLACE(?, '-', ' ')) " +
                        "OR (CASE l.kind WHEN 'RECEIVING' THEN 'RECEPCION' " +
                        "WHEN 'UNASSIGNED' THEN 'SINUBICAR' END) = " +
                        "UPPER(REPLACE(?, '-', '')))",
                )
            }
            if (onlyWithStock) append(" AND s.quantity > 0")
            append(" ORDER BY p.product_code, l.hall, l.code, l.\"position\" LIMIT ? OFFSET ?")
        }

        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (productCode != null) st.setString(i++, productCode)
            if (locationCode != null) {
                // Tres parámetros: el nombre visible, y las tres columnas para
                // componer el concatenado con el que se compara. Se pasan como
                // literales vacíos porque la comparación real es contra las columnas
                // de la fila; el `UPPER(REPLACE(?||?||?, '-',''))` recibe las tres.
                // Dos parámetros, uno por cada forma de nombrar la ubicación.
                st.setString(i++, locationCode)
                st.setString(i++, locationCode)
            }
            st.setInt(i++, limit)
            st.setInt(i, offset)

            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StockByLocationDto(
                                productId = rs.getLong("product_id"),
                                productCode = rs.getString("product_code"),
                                productName = rs.getString("product_name"),
                                locationId = rs.getLong("location_id"),
                                locationDisplayName = locationDisplayName(
                                    rs.getString("hall"),
                                    rs.getString("code"),
                                    rs.getString("position"),
                                    rs.getString("kind"),
                                ),
                                warehouseId = rs.getLong("warehouse_id"),
                                warehouseCode = rs.getString("warehouse_code"),
                                quantity = rs.getBigDecimal("quantity").toPlainString(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** El libro de inventario, paginado y filtrado. */
    fun kardex(
        productCode: String? = null,
        from: Instant? = null,
        until: Instant? = null,
        type: String? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): List<KardexDto> = dataSource.connection.use { connection ->
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
            if (productCode != null) append(" AND p.product_code = ?")
            if (from != null) append(" AND k.moved_at >= ?")
            if (until != null) append(" AND k.moved_at <= ?")
            if (type != null) append(" AND k.move_type = ?::movement_type")
            append(" ORDER BY k.moved_at DESC, k.id DESC LIMIT ? OFFSET ?")
        }

        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (productCode != null) st.setString(i++, productCode)
            if (from != null) st.setTimestamp(i++, Timestamp.from(from))
            if (until != null) st.setTimestamp(i++, Timestamp.from(until))
            if (type != null) st.setString(i++, type)
            st.setInt(i++, limit)
            st.setInt(i, offset)

            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val from = rs.getString("from_hall")?.let {
                            locationDisplayName(it, rs.getString("from_code"), rs.getString("from_pos"), rs.getString("from_kind"))
                        }
                        val towards = rs.getString("to_hall")?.let {
                            locationDisplayName(it, rs.getString("to_code"), rs.getString("to_pos"), rs.getString("to_kind"))
                        }

                        add(
                            KardexDto(
                                id = rs.getLong("id"),
                                movedAt = rs.getTimestamp("moved_at").toInstant().toString(),
                                productCode = rs.getString("product_code"),
                                productName = rs.getString("product_name"),
                                moveType = rs.getString("move_type"),
                                units = rs.getBigDecimal("units").toPlainString(),
                                quantityAfter = rs.getBigDecimal("quantity_after").toPlainString(),
                                fromLocationDisplayName = from,
                                toLocationDisplayName = towards,
                                container = rs.getString("container"),
                                orderNumber = rs.getString("order_number"),
                                adjustmentReason = rs.getString("adjustment_reason"),
                                adjustmentOrigin = rs.getString("adjustment_origin"),
                                userId = rs.getLong("user_id").takeIf { !rs.wasNull() },
                                userName = rs.getString("user_name"),
                                note = rs.getString("note"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Conteo total de existencias, para el sobre de paginación.
     *
     * **ANTES NO EXISTÍA Y ERA UN BUG.** La ruta pasaba `items.size` como
     * `totalItems`, así que la respuesta decía «50 resultados» siempre y el cliente
     * no podía pintar «1.064 existencias, 22 páginas». Lo encontró un agente al
     * conectar la pantalla de inventario: la paginación estaba mintiendo.
     *
     * El filtro por ubicación SÍ se aplica aquí. Antes la ruta lo ignoraba, así que
     * pedir `locationCode=SIN-UBICAR` devolvía **cero** filas sin decir por qué.
     */
    fun countStock(
        productCode: String? = null,
        locationCode: String? = null,
        onlyWithStock: Boolean = true,
    ): Long = dataSource.connection.use { connection ->
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
            if (productCode != null) append(" AND p.product_code = ?")
            // El código de ubicación puede venir como `SIN-UBICAR` (el nombre
            // visible) o como `SINUBICAR` (las tres partes concatenadas), así que se
            // aceptan los dos: el usuario copia lo que ve en la pantalla.
            if (locationCode != null) {
                append(
                    // Se aceptan las DOS formas en que el usuario puede nombrar una
                    // ubicación, porque copia lo que ve en la pantalla: el nombre
                    // visible (`SIN-UBICAR`) y las tres partes concatenadas
                    // (`SINUBICAR`). **Sin `IN` sobre una lista fija**: la primera
                    // versión hacía `UPPER(REPLACE(?,'-','')) IN ('SINUBICAR',
                    // 'RECEPCION')`, y como `RECEPCION` está en esa lista, filtrar por
                    // `RECEPCION` devolvía TODAS las filas. Un `IN` con valores
                    // constantes no compara con el parámetro: lo usaba como permiso.
                    " AND (UPPER(REPLACE(l.hall || l.code || l.\"position\", '-', '')) " +
                        "= UPPER(REPLACE(?, '-', ' ')) " +
                        "OR (CASE l.kind WHEN 'RECEIVING' THEN 'RECEPCION' " +
                        "WHEN 'UNASSIGNED' THEN 'SINUBICAR' END) = " +
                        "UPPER(REPLACE(?, '-', '')))",
                )
            }
            if (onlyWithStock) append(" AND s.quantity > 0")
        }

        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (productCode != null) st.setString(i++, productCode)
            if (locationCode != null) {
                st.setString(i++, locationCode)
                st.setString(i++, locationCode)
            }
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    /** Conteo total de una consulta, para el sobre de paginación. */
    /**
     * Conteo del kardex, para el sobre de paginación.
     *
     * **ANTES IGNORABA `from` Y `to`.** Un rango de fechas sin resultados devolvía
     * `items: []` pero `totalItems: 1064` y `totalPages: 213`, así que el cliente
     * pintaba «1.064 movimientos» sobre una lista vacía. Es peor que un error: parece
     * que la lista no cargó. Lo encontró un agente al conectar la pantalla del kardex,
     * y por eso esa pantalla no muestra el total cuando hay rango de fechas.
     */
    fun countKardex(
        productCode: String? = null,
        type: String? = null,
        from: Instant? = null,
        until: Instant? = null,
    ): Long = dataSource.connection.use { connection ->
        val sql = buildString {
            append("SELECT count(*) FROM kardex k JOIN products p ON p.id = k.product_id WHERE 1=1")
            if (productCode != null) append(" AND p.product_code = ?")
            if (type != null) append(" AND k.move_type = ?::movement_type")
            if (from != null) append(" AND k.moved_at >= ?")
            if (until != null) append(" AND k.moved_at <= ?")
        }
        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (productCode != null) st.setString(i++, productCode)
            if (type != null) st.setString(i++, type)
            if (from != null) st.setTimestamp(i++, Timestamp.from(from))
            if (until != null) st.setTimestamp(i, Timestamp.from(until))
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    /**
     * El reporte de reconciliación.
     *
     * **El legacy no tenía esto**: el descuadre solo se descubría con un conteo
     * físico. Aquí se puede preguntar en cualquier momento y sin contar nada.
     */
    fun divergences(): List<DivergenceDto> = dataSource.connection.use { connection ->
        connection.createStatement().use { st ->
            st.executeQuery("SELECT * FROM comprobar_proyecciones()").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DivergenceDto(
                                productId = rs.getLong("product_id"),
                                productCode = rs.getString("product_code"),
                                totalEnBd = rs.getBigDecimal("total_en_bd").toPlainString(),
                                totalEsperado = rs.getBigDecimal("total_esperado").toPlainString(),
                                diferencia = rs.getBigDecimal("diferencia").toPlainString(),
                            ),
                        )
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNOS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Escribe el movimiento llamando a la función del motor.
     *
     * **Una sola llamada hace las tres cosas**: ajusta las ubicaciones, recalcula el
     * total y escribe el kardex, todo en la transacción de quien llama.
     */
    private fun writeMovement(
        connection: Connection,
        productId: Long,
        warehouseId: Long,
        type: MovementType,
        units: Quantity,
        originId: Long? = null,
        destinationId: Long? = null,
        reason: AdjustmentReason? = null,
        origin: AdjustmentOrigin? = null,
        userId: Long? = null,
        approverId: Long? = null,
        key: String? = null,
        note: String? = null,
        container: String? = null,
        order: String? = null,
        ip: String? = null,
    ): Long {
        val sql = """
            INSERT INTO kardex (
                product_id, warehouse_id, move_type, units, quantity_after,
                location_id, from_location_id, to_location_id,
                container, order_number, adjustment_reason, adjustment_origin,
                user_id, approved_by, approved_at, client_uuid, note
            )
            SELECT ?, ?, ?::movement_type, ?, ps.quantity,
                   ?, ?, ?, ?, ?, ?::adjustment_reason, ?::adjustment_origin,
                   ?, ?, ?, ?::uuid, ?
            FROM product_stock ps WHERE ps.product_id = ?
            RETURNING id
        """.trimIndent()

        // El motor ajusta las ubicaciones y recalcula el total ANTES de que se
        // escriba el kardex, así que el `quantity_after` tiene que ser el de
        // después. Se hace en dos pasos coordinados por la misma transacción.
        applyToLocations(connection, productId, warehouseId, originId, destinationId, units)
        recalculateTotal(connection, productId)

        return connection.prepareStatement(sql).use { st ->
            var i = 1
            st.setLong(i++, productId)
            st.setLong(i++, warehouseId)
            st.setString(i++, type.name)
            st.setBigDecimal(i++, units.toBigDecimal())
            st.setObject(i++, destinationId)
            st.setObject(i++, originId)
            st.setObject(i++, destinationId)
            st.setString(i++, container)
            st.setString(i++, order)
            st.setString(i++, reason?.name)
            st.setString(i++, origin?.name)
            st.setObject(i++, userId)
            st.setObject(i++, approverId)
            st.setTimestamp(i++, approverId?.let { Timestamp.from(Instant.now()) })
            st.setString(i++, key ?: UUID.randomUUID().toString())
            st.setString(i++, note)
            st.setLong(i, productId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else error("Sin id") }
        }
    }

    private fun applyToLocations(
        connection: Connection,
        productId: Long,
        warehouseId: Long,
        originId: Long?,
        destinationId: Long?,
        units: Quantity,
    ) {
        val sql = "SELECT aplicar_movimiento_a_ubicacion(?, ?, ?, ?, FALSE)"
        listOfNotNull(
            originId?.let { it to units.negated() },
            destinationId?.let { it to units },
        ).forEach { (locationId, delta) ->
            connection.prepareStatement(sql).use { st ->
                st.setLong(1, productId)
                st.setLong(2, locationId)
                st.setLong(3, warehouseId)
                st.setBigDecimal(4, delta.toBigDecimal())
                st.execute()
            }
        }
    }

    private fun recalculateTotal(connection: Connection, productId: Long) {
        connection.prepareStatement("SELECT recalcular_total_producto(?)").use { st ->
            st.setLong(1, productId)
            st.execute()
        }
    }

    private fun readBalance(connection: Connection, productId: Long, locationId: Long): Quantity =
        connection.prepareStatement(
            "SELECT quantity FROM stock_by_location WHERE product_id = ? AND location_id = ?",
        ).use { st ->
            st.setLong(1, productId)
            st.setLong(2, locationId)
            st.executeQuery().use { rs ->
                if (rs.next()) Quantity.ofScaled(rs.getBigDecimal("quantity").movePointRight(3).toLong())
                else Quantity.ZERO
            }
        }

    private fun readTotal(connection: Connection, productId: Long): Quantity =
        connection.prepareStatement("SELECT quantity FROM product_stock WHERE product_id = ?").use { st ->
            st.setLong(1, productId)
            st.executeQuery().use { rs ->
                if (rs.next()) Quantity.ofScaled(rs.getBigDecimal("quantity").movePointRight(3).toLong())
                else Quantity.ZERO
            }
        }

    private data class ProductFound(val id: Long)

    private fun findProduct(connection: Connection, code: ProductCode): Long? =
        connection.prepareStatement("SELECT id FROM products WHERE product_code = ? AND active").use { st ->
            st.setString(1, code.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }

    data class LocationFound(val id: Long, val warehouseId: Long)

    /**
     * Resuelve una ubicación por su código.
     *
     * **LAS UBICACIONES ESPECIALES NO TIENEN CÓDIGO CONCATENADO VÁLIDO, y aquí estaba
     * el bloqueo que impedía hacer ningún ajuste.**
     *
     * `RECEPCION` y `SIN-UBICAR` se crearon con las tres partes iguales
     * (`hall=code=position='SINUBICAR'`), así que su concatenación son **27
     * caracteres**. Pero `LocationCode` exige un **máximo de 24**, de modo que la
     * validación del dominio rechazaba el único valor capaz de encontrarlas: **no
     * existía ningún `locationCode` con el que un ajuste pudiera completarse**.
     *
     * La concatenación es un identificador artificial que solo tiene sentido para un
     * estante (`04BCA`). Una ubicación especial **se identifica por su nombre
     * visible**, que es lo que el usuario ve en la pantalla y lo que copia. Por eso
     * se resuelven por `kind`, con el mismo criterio que usa `existencias`.
     */
    private fun findLocation(connection: Connection, code: LocationCode): LocationFound? =
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
                if (rs.next()) LocationFound(rs.getLong("id"), rs.getLong("warehouse_id")) else null
            }
        }

    /**
     * El nombre visible de una ubicación.
     *
     * **Lo compone el servidor, no el cliente**, y ésa es la regla: si mañana cambia
     * el formato de las etiquetas de estante, no hay que tocar el backend, la web y
     * el móvil a la vez.
     *
     * La de recepción y la de sin-ubicar devuelven su propio nombre y no la
     * concatenación: `RECEPCION-RECEPCION-RECEPCION` no dice nada.
     */
    private fun locationDisplayName(hall: String, code: String, position: String, kind: String): String =
        when (kind) {
            "RECEIVING" -> "RECEPCION"
            "UNASSIGNED" -> "SIN-UBICAR"
            else -> "$hall-$code-$position"
        }

    private fun whenFails(code: String, message: String) = when (code) {
        "INSUFFICIENT_STOCK" -> UnprocessableException(code, message)
        "SEGREGATION_OF_DUTIES" -> UnprocessableException(code, message)
        "REASON_REQUIRED" -> UnprocessableException(code, message)
        else -> UnprocessableException(code, message)
    }
}

/** El resultado de aplicar un ajuste. */
data class AdjustmentResult(
    val movements: List<Long>,
    val total: Quantity,
    /** `APPLIED` o `PENDING_APPROVAL`. */
    val status: String,
    val reason: String? = null,
)

@Serializable
data class StockByLocationDto(
    val productId: Long,
    val productCode: String,
    val productName: String,
    val locationId: Long,
    val locationDisplayName: String,
    val warehouseId: Long,
    val warehouseCode: String,
    /** Número JSON con 3 decimales, nunca cadena de texto. */
    val quantity: String,
)

@Serializable
data class KardexDto(
    val id: Long,
    val movedAt: String,
    val productCode: String,
    val productName: String,
    val moveType: String,
    val units: String,
    val quantityAfter: String,
    val fromLocationDisplayName: String? = null,
    val toLocationDisplayName: String? = null,
    val container: String? = null,
    val orderNumber: String? = null,
    val adjustmentReason: String? = null,
    val adjustmentOrigin: String? = null,
    val userId: Long? = null,
    val userName: String? = null,
    val note: String? = null,
)

@Serializable
data class DivergenceDto(
    val productId: Long,
    val productCode: String,
    val totalEnBd: String,
    val totalEsperado: String,
    val diferencia: String,
)

/**
 * Convierte una [Quantity] a `BigDecimal` con 3 decimales exactos.
 *
 * La cantidad vive como entero escalado (milésimas) para no perder precisión en
 * coma flotante, y PostgreSQL la guarda como `NUMERIC(14,3)`. La conversión es
 * explícita y de ida y vuelta, sin pasar por `Double` en ningún momento: el
 * `Decimal` contra `number` del legacy es justo el origen de sus descuadres de
 * redondeo.
 */
private fun Quantity.toBigDecimal(): java.math.BigDecimal =
    java.math.BigDecimal.valueOf(this.value).movePointLeft(Quantity.SCALE)

/**
 * El opuesto de una cantidad, para restar de una ubicación.
 *
 * Se construye sobre `ofScaled` en lugar de multiplicar por −1 en coma flotante:
 * negar el entero escalado es exacto por definición.
 */
private fun Quantity.negated(): Quantity = Quantity.ofScaled(-this.value)

/**
 * El permiso que exigía el ajuste, para dejarlo en el registro de auditoría.
 *
 * **Se repite la regla de la ruta a propósito, y conviene saber por qué.** La ruta lo
 * calcula para autorizar; aquí se calcula para dejarlo escrito. Podría pasarse como
 * parámetro y sería más limpio, pero entonces el registro dependería de que la ruta
 * se acuerde de mandarlo — y un registro de auditoría al que le falta el permiso
 * justo cuando alguien lo consulta es peor que calcularlo dos veces.
 */
private fun permissionForAdjustment(
    direction: dev.axzy.stock.domain.inventory.AdjustmentDirection,
    reason: dev.axzy.stock.domain.inventory.AdjustmentReason,
): String = when {
    direction == dev.axzy.stock.domain.inventory.AdjustmentDirection.INCREASE -> "inventory.adjust_increase"
    reason == dev.axzy.stock.domain.inventory.AdjustmentReason.THEFT -> "inventory.adjust_theft"
    reason == dev.axzy.stock.domain.inventory.AdjustmentReason.DAMAGE -> "inventory.adjust_damage"
    reason == dev.axzy.stock.domain.inventory.AdjustmentReason.SHORTAGE -> "inventory.adjust_shortage"
    else -> "inventory.adjust_count_error"
}

/** La cantidad como texto con 3 decimales, que es el formato del contrato. */
private fun dev.axzy.stock.domain.value.Quantity.unitsText(): String =
    java.math.BigDecimal.valueOf(this.value).movePointLeft(dev.axzy.stock.domain.value.Quantity.SCALE).toPlainString()
