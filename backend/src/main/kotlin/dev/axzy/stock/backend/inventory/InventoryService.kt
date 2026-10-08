package dev.axzy.stock.backend.inventory

import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.identity.esDireccionIp
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
    fun ajustar(
        solicitud: AdjustmentRequest,
        aprobadorId: Long? = null,
        ip: String? = null,
    ): ResultadoDeAjuste = dataSource.connection.use { conexion ->
        conexion.autoCommit = false

        try {
            val productoId = buscarProducto(conexion, solicitud.productCode)
                ?: throw NotFoundException(
                    "El producto '${solicitud.productCode.value}' no existe.",
                    code = "PRODUCT_NOT_FOUND",
                )

            val ubicacion = buscarUbicacion(conexion, solicitud.locationCode)
                ?: throw NotFoundException(
                    "La ubicación '${solicitud.locationCode.value}' no existe.",
                    code = "LOCATION_NOT_FOUND",
                )

            // El saldo se lee DENTRO de la transacción, no antes. Leerlo fuera
            // abriría una ventana en la que el saldo cambia entre la comprobación
            // y el movimiento.
            val disponible = leerSaldo(conexion, productoId, ubicacion.id)

            val decision = AdjustmentPolicy.decide(
                request = solicitud,
                approverId = aprobadorId,
                available = disponible,
            )

            when (decision) {
                is AdjustmentPolicy.Decision.Reject -> {
                    conexion.rollback()
                    throw cuandoFalla(decision.error.code, decision.error.message)
                }

                is AdjustmentPolicy.Decision.PendingApproval -> {
                    // **No se escribe nada.** Un movimiento que espera aprobación y
                    // ya movió el inventario sería un cambio de stock sin autorizar,
                    // que es justo lo que la aprobación pretende evitar.
                    conexion.rollback()
                    return@use ResultadoDeAjuste(
                        movimientos = emptyList(),
                        total = disponible,
                        estado = "PENDING_APPROVAL",
                        motivo = decision.reason,
                    )
                }

                AdjustmentPolicy.Decision.Apply -> {
                    val kardexId = escribirMovimiento(
                        conexion = conexion,
                        productoId = productoId,
                        bodegaId = ubicacion.warehouseId,
                        tipo = solicitud.movementType,
                        unidades = solicitud.quantity,
                        // Un aumento ENTRA a la ubicación; una merma SALE de ella.
                        origenId = if (solicitud.direction == AdjustmentDirection.DECREASE) ubicacion.id else null,
                        destinoId = if (solicitud.direction == AdjustmentDirection.INCREASE) ubicacion.id else null,
                        motivo = solicitud.reason,
                        origen = solicitud.origin,
                        usuarioId = solicitud.requestedBy,
                        aprobadorId = aprobadorId,
                        clave = solicitud.idempotencyKey,
                        nota = solicitud.note,
                        ip = ip,
                    )

                    val totalNuevo = leerTotal(conexion, productoId)
                    conexion.commit()

                    ResultadoDeAjuste(
                        movimientos = listOf(kardexId),
                        total = totalNuevo,
                        estado = "APPLIED",
                    )
                }
            }
        } catch (e: Exception) {
            runCatching { conexion.rollback() }
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
    fun ubicar(
        productCode: ProductCode,
        desde: LocationCode,
        hasta: LocationCode,
        cantidad: Quantity,
        usuarioId: Long,
        clave: String? = null,
    ): Long = dataSource.connection.use { conexion ->
        conexion.autoCommit = false
        try {
            val productoId = buscarProducto(conexion, productCode)
                ?: throw NotFoundException("El producto '${productCode.value}' no existe.", "PRODUCT_NOT_FOUND")

            val origen = buscarUbicacion(conexion, desde)
                ?: throw NotFoundException("La ubicación '${desde.value}' no existe.", "LOCATION_NOT_FOUND")

            val destino = buscarUbicacion(conexion, hasta)
                ?: throw NotFoundException("La ubicación '${hasta.value}' no existe.", "LOCATION_NOT_FOUND")

            if (origen.id == destino.id) {
                throw UnprocessableException(
                    "SAME_SOURCE_AND_DESTINATION",
                    "El origen y el destino son la misma ubicación: no hay traslado.",
                )
            }

            val id = escribirMovimiento(
                conexion = conexion,
                productoId = productoId,
                bodegaId = destino.warehouseId,
                tipo = MovementType.LOCATE,
                unidades = cantidad,
                origenId = origen.id,
                destinoId = destino.id,
                usuarioId = usuarioId,
                clave = clave,
            )

            conexion.commit()
            id
        } catch (e: Exception) {
            runCatching { conexion.rollback() }
            throw e
        }
    }

    /** Traslado entre estantes de la misma bodega. */
    fun moverDentroDeBodega(
        productCode: ProductCode,
        desde: LocationCode,
        hasta: LocationCode,
        cantidad: Quantity,
        usuarioId: Long,
        clave: String? = null,
    ): Long = ubicar(productCode, desde, hasta, cantidad, usuarioId, clave)

    // ═══════════════════════════════════════════════════════════════════════
    // CONSULTAS
    // ═══════════════════════════════════════════════════════════════════════

    /** Existencias por producto y ubicación. */
    fun existencias(
        productCode: String? = null,
        locationCode: String? = null,
        soloConExistencia: Boolean = true,
        offset: Int = 0,
        limite: Int = 50,
    ): List<ExistenciaDto> = dataSource.connection.use { conexion ->
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
            if (soloConExistencia) append(" AND s.quantity > 0")
            append(" ORDER BY p.product_code, l.hall, l.code, l.\"position\" LIMIT ? OFFSET ?")
        }

        conexion.prepareStatement(sql).use { st ->
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
            st.setInt(i++, limite)
            st.setInt(i, offset)

            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ExistenciaDto(
                                productId = rs.getLong("product_id"),
                                productCode = rs.getString("product_code"),
                                productName = rs.getString("product_name"),
                                locationId = rs.getLong("location_id"),
                                locationDisplayName = nombreDeUbicacion(
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
        desde: Instant? = null,
        hasta: Instant? = null,
        tipo: String? = null,
        offset: Int = 0,
        limite: Int = 50,
    ): List<KardexDto> = dataSource.connection.use { conexion ->
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
            if (desde != null) append(" AND k.moved_at >= ?")
            if (hasta != null) append(" AND k.moved_at <= ?")
            if (tipo != null) append(" AND k.move_type = ?::movement_type")
            append(" ORDER BY k.moved_at DESC, k.id DESC LIMIT ? OFFSET ?")
        }

        conexion.prepareStatement(sql).use { st ->
            var i = 1
            if (productCode != null) st.setString(i++, productCode)
            if (desde != null) st.setTimestamp(i++, Timestamp.from(desde))
            if (hasta != null) st.setTimestamp(i++, Timestamp.from(hasta))
            if (tipo != null) st.setString(i++, tipo)
            st.setInt(i++, limite)
            st.setInt(i, offset)

            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val desde_ = rs.getString("from_hall")?.let {
                            nombreDeUbicacion(it, rs.getString("from_code"), rs.getString("from_pos"), rs.getString("from_kind"))
                        }
                        val hacia = rs.getString("to_hall")?.let {
                            nombreDeUbicacion(it, rs.getString("to_code"), rs.getString("to_pos"), rs.getString("to_kind"))
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
                                fromLocationDisplayName = desde_,
                                toLocationDisplayName = hacia,
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
    fun contarExistencias(
        productCode: String? = null,
        locationCode: String? = null,
        soloConExistencia: Boolean = true,
    ): Long = dataSource.connection.use { conexion ->
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
            if (soloConExistencia) append(" AND s.quantity > 0")
        }

        conexion.prepareStatement(sql).use { st ->
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
    fun contarKardex(productCode: String? = null, tipo: String? = null): Long =
        dataSource.connection.use { conexion ->
            val sql = buildString {
                append("SELECT count(*) FROM kardex k JOIN products p ON p.id = k.product_id WHERE 1=1")
                if (productCode != null) append(" AND p.product_code = ?")
                if (tipo != null) append(" AND k.move_type = ?::movement_type")
            }
            conexion.prepareStatement(sql).use { st ->
                var i = 1
                if (productCode != null) st.setString(i++, productCode)
                if (tipo != null) st.setString(i++, tipo)
                st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }

    /**
     * El reporte de reconciliación.
     *
     * **El legacy no tenía esto**: el descuadre solo se descubría con un conteo
     * físico. Aquí se puede preguntar en cualquier momento y sin contar nada.
     */
    fun divergencias(): List<DivergenciaDto> = dataSource.connection.use { conexion ->
        conexion.createStatement().use { st ->
            st.executeQuery("SELECT * FROM comprobar_proyecciones()").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DivergenciaDto(
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
    private fun escribirMovimiento(
        conexion: Connection,
        productoId: Long,
        bodegaId: Long,
        tipo: MovementType,
        unidades: Quantity,
        origenId: Long? = null,
        destinoId: Long? = null,
        motivo: AdjustmentReason? = null,
        origen: AdjustmentOrigin? = null,
        usuarioId: Long? = null,
        aprobadorId: Long? = null,
        clave: String? = null,
        nota: String? = null,
        contenedor: String? = null,
        orden: String? = null,
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
        aplicarEnUbicaciones(conexion, productoId, bodegaId, origenId, destinoId, unidades)
        recalcularTotal(conexion, productoId)

        return conexion.prepareStatement(sql).use { st ->
            var i = 1
            st.setLong(i++, productoId)
            st.setLong(i++, bodegaId)
            st.setString(i++, tipo.name)
            st.setBigDecimal(i++, unidades.aBigDecimal())
            st.setObject(i++, destinoId)
            st.setObject(i++, origenId)
            st.setObject(i++, destinoId)
            st.setString(i++, contenedor)
            st.setString(i++, orden)
            st.setString(i++, motivo?.name)
            st.setString(i++, origen?.name)
            st.setObject(i++, usuarioId)
            st.setObject(i++, aprobadorId)
            st.setTimestamp(i++, aprobadorId?.let { Timestamp.from(Instant.now()) })
            st.setString(i++, clave ?: UUID.randomUUID().toString())
            st.setString(i++, nota)
            st.setLong(i, productoId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else error("Sin id") }
        }
    }

    private fun aplicarEnUbicaciones(
        conexion: Connection,
        productoId: Long,
        bodegaId: Long,
        origenId: Long?,
        destinoId: Long?,
        unidades: Quantity,
    ) {
        val sql = "SELECT aplicar_movimiento_a_ubicacion(?, ?, ?, ?, FALSE)"
        listOfNotNull(
            origenId?.let { it to unidades.negada() },
            destinoId?.let { it to unidades },
        ).forEach { (ubicacionId, delta) ->
            conexion.prepareStatement(sql).use { st ->
                st.setLong(1, productoId)
                st.setLong(2, ubicacionId)
                st.setLong(3, bodegaId)
                st.setBigDecimal(4, delta.aBigDecimal())
                st.execute()
            }
        }
    }

    private fun recalcularTotal(conexion: Connection, productoId: Long) {
        conexion.prepareStatement("SELECT recalcular_total_producto(?)").use { st ->
            st.setLong(1, productoId)
            st.execute()
        }
    }

    private fun leerSaldo(conexion: Connection, productoId: Long, ubicacionId: Long): Quantity =
        conexion.prepareStatement(
            "SELECT quantity FROM stock_by_location WHERE product_id = ? AND location_id = ?",
        ).use { st ->
            st.setLong(1, productoId)
            st.setLong(2, ubicacionId)
            st.executeQuery().use { rs ->
                if (rs.next()) Quantity.ofScaled(rs.getBigDecimal("quantity").movePointRight(3).toLong())
                else Quantity.ZERO
            }
        }

    private fun leerTotal(conexion: Connection, productoId: Long): Quantity =
        conexion.prepareStatement("SELECT quantity FROM product_stock WHERE product_id = ?").use { st ->
            st.setLong(1, productoId)
            st.executeQuery().use { rs ->
                if (rs.next()) Quantity.ofScaled(rs.getBigDecimal("quantity").movePointRight(3).toLong())
                else Quantity.ZERO
            }
        }

    private data class ProductoEncontrado(val id: Long)

    private fun buscarProducto(conexion: Connection, code: ProductCode): Long? =
        conexion.prepareStatement("SELECT id FROM products WHERE product_code = ? AND active").use { st ->
            st.setString(1, code.value)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }

    data class UbicacionEncontrada(val id: Long, val warehouseId: Long)

    private fun buscarUbicacion(conexion: Connection, code: LocationCode): UbicacionEncontrada? =
        conexion.prepareStatement(
            """
            SELECT id, warehouse_id FROM locations
            WHERE (hall || code || "position") = ? AND active
            """.trimIndent(),
        ).use { st ->
            st.setString(1, code.value)
            st.executeQuery().use { rs ->
                if (rs.next()) UbicacionEncontrada(rs.getLong("id"), rs.getLong("warehouse_id")) else null
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
    private fun nombreDeUbicacion(hall: String, code: String, position: String, kind: String): String =
        when (kind) {
            "RECEIVING" -> "RECEPCION"
            "UNASSIGNED" -> "SIN-UBICAR"
            else -> "$hall-$code-$position"
        }

    private fun cuandoFalla(code: String, message: String) = when (code) {
        "INSUFFICIENT_STOCK" -> UnprocessableException(code, message)
        "SEGREGATION_OF_DUTIES" -> UnprocessableException(code, message)
        "REASON_REQUIRED" -> UnprocessableException(code, message)
        else -> UnprocessableException(code, message)
    }
}

/** El resultado de aplicar un ajuste. */
data class ResultadoDeAjuste(
    val movimientos: List<Long>,
    val total: Quantity,
    /** `APPLIED` o `PENDING_APPROVAL`. */
    val estado: String,
    val motivo: String? = null,
)

@Serializable
data class ExistenciaDto(
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
data class DivergenciaDto(
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
private fun Quantity.aBigDecimal(): java.math.BigDecimal =
    java.math.BigDecimal.valueOf(this.value).movePointLeft(Quantity.SCALE)

/**
 * El opuesto de una cantidad, para restar de una ubicación.
 *
 * Se construye sobre `ofScaled` en lugar de multiplicar por −1 en coma flotante:
 * negar el entero escalado es exacto por definición.
 */
private fun Quantity.negada(): Quantity = Quantity.ofScaled(-this.value)
