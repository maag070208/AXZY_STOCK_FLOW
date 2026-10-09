package dev.axzy.stock.backend.order

import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.inventory.InventoryService
import dev.axzy.stock.domain.inventory.AdjustmentDirection
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.fulfillment.Order
import dev.axzy.stock.domain.fulfillment.OrderLine
import dev.axzy.stock.domain.fulfillment.OrderLineStatus
import dev.axzy.stock.domain.fulfillment.OrderStatus
import dev.axzy.stock.domain.inventory.AdjustmentRequest
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SURTIDO DE ÓRDENES (contrato §6)
 *
 * ── LA REGLA DEL DESCUENTO, QUE ES LO QUE MÁS FÁCIL SERÍA EQUIVOCAR ────────
 *
 * > **`supplied_quantity` es SIEMPRE lo ya descontado del inventario.**
 *
 * | Acción | Descuenta | Por qué |
 * |---|---|---|
 * | **Empacar** | la amount empacada | sale del estante ahora |
 * | **Confirmar** | **lo que FALTA** hasta `quantity` | lo empacado ya se descontó |
 *
 * Si confirmar descontara `quantity` entera, una línea de 10 con 7 empacadas
 * descontaría 17 unidades en total. **El inventario bajaría de más y solo se
 * descubriría al cuadrar el almacén**, donde no se distingue de una merma real.
 *
 * ── POR QUÉ `FULFILLMENT_OUT` Y NO UN TIPO PROPIO ──────────────────────────
 *
 * El movimiento del ledger lo escribe `registrar_movimiento` con
 * `FULFILLMENT_OUT`, que ya existe en el enum desde V3 y **significa exactamente
 * esto**: salida por surtido de una orden. Inventar un tipo nuevo obligaría a
 * migrar el enum y a que los reportes lo contemplaran.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class OrderService(
    private val dataSource: DataSource,
    private val inventory: InventoryService = InventoryService(dataSource),
) {

    // ═════════════════════════════════════════════════════════════════════════
    // LECTURA
    // ═════════════════════════════════════════════════════════════════════════

    fun byId(id: Long): OrderDetailResponse? = dataSource.connection.use { connection ->
        val cabecera = readHeader(connection, id) ?: return@use null
        readLines(connection, id).let { cabecera.copy(lines = it) }
    }

    fun list(
        status: String?,
        search: String?,
        offset: Int,
        limit: Int,
    ): List<OrderResponse> = dataSource.connection.use { connection ->
        val sql = buildString {
            append("$HEADER_SELECT WHERE NOT o.deleted ")
            if (status != null) append(" AND o.status = ?::order_status ")
            if (!search.isNullOrBlank()) append(" AND (o.order_number ILIKE ? OR o.concept ILIKE ?) ")
            append(" ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?")
        }
        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (status != null) st.setString(i++, status)
            if (!search.isNullOrBlank()) {
                val patron = "%${search.trim()}%"
                st.setString(i++, patron)
                st.setString(i++, patron)
            }
            st.setInt(i++, limit)
            st.setInt(i, offset)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toOrderResponse()) }
            }
        }
    }

    fun count(status: String?, search: String?): Long = dataSource.connection.use { connection ->
        val sql = buildString {
            append("SELECT count(*) FROM orders o WHERE NOT o.deleted ")
            if (status != null) append(" AND o.status = ?::order_status ")
            if (!search.isNullOrBlank()) append(" AND (o.order_number ILIKE ? OR o.concept ILIKE ?) ")
        }
        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (status != null) st.setString(i++, status)
            if (!search.isNullOrBlank()) {
                val patron = "%${search.trim()}%"
                st.setString(i++, patron)
                st.setString(i++, patron)
            }
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ALTA
    // ═════════════════════════════════════════════════════════════════════════

    fun create(request: OrderCreateRequest, userId: Long?): OrderDetailResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (request.lines.isEmpty()) {
                    throw UnprocessableException(
                        code = "EMPTY_ORDER",
                        message = "La orden no tiene líneas: una orden sin líneas no se puede surtir.",
                    )
                }

                val number = request.orderNumber?.trim()?.takeIf { it.isNotEmpty() }
                    ?: nextOrderNumber(connection)

                val id = connection.prepareStatement(
                    "INSERT INTO orders (order_number, packaging_date, status, customer_code, concept) " +
                        "VALUES (?, ?::date, 'PENDING', ?, ?) RETURNING id",
                ).use { st ->
                    st.setString(1, number)
                    st.setString(2, (request.packagingDate ?: LocalDate.now().toString()))
                    st.setString(3, request.customerCode?.trim()?.takeIf { it.isNotEmpty() })
                    st.setString(4, request.concept?.trim()?.takeIf { it.isNotEmpty() })
                    st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }

                request.lines.forEach { line ->
                    val product = findProduct(connection, line.productCode)
                        ?: throw UnprocessableException(
                            code = "PRODUCT_NOT_FOUND",
                            message = "El product '${line.productCode}' no existe o está inactivo.",
                            details = mapOf("productCode" to line.productCode),
                        )
                    val amount = parsePositive(line.quantity, "quantity")

                    // ── La línea nace CON su ubicación, y era un fallo que no ──
                    //
                    // `resolveLocation` cae al `location_id` de la línea cuando el
                    // empaque no manda ubicación. Como la línea se insertaba con ese
                    // campo NULO, la caída no servía de nada y empacar respondía
                    // `422 LOCATION_REQUIRED` **siempre que el cliente no mandara una
                    // ubicación**. El defecto no se veía en las pruebas del servicio
                    // porque todas pasaban `locationId` explícito; lo encontró el
                    // navegador, que empaca sin elegir estante.
                    //
                    // Se elige la ubicación donde el producto TIENE mercancía: es la
                    // única que puede servir de origen, y resolverla aquí ahorra al
                    // operador elegir estante en cada línea. Si no hay stock en
                    // ningún sitio, queda nula y el empaque pedirá la ubicación — que
                    // es correcto, porque entonces no se puede empacar de ninguna.
                    val sourceLocation = connection.prepareStatement(
                        """
                        SELECT s.location_id
                        FROM stock_by_location s
                        WHERE s.product_id = ? AND s.quantity > 0
                        ORDER BY s.quantity DESC, s.location_id
                        LIMIT 1
                        """.trimIndent(),
                    ).use { st ->
                        st.setLong(1, product.id)
                        st.executeQuery().use { rs ->
                            if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null
                        }
                    }

                    connection.prepareStatement(
                        "INSERT INTO order_lines " +
                            "(order_id, product_id, unit_measure_id, quantity, location_id) " +
                            "VALUES (?, ?, ?, ?::numeric, ?)",
                    ).use { st ->
                        st.setLong(1, id)
                        st.setLong(2, product.id)
                        st.setLong(3, product.unitMeasureId)
                        st.setBigDecimal(4, amount)
                        if (sourceLocation != null) {
                            st.setLong(5, sourceLocation)
                        } else {
                            st.setNull(5, java.sql.Types.BIGINT)
                        }
                        st.executeUpdate()
                    }
                }

                connection.commit()
                byId(id)!!
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    // ═════════════════════════════════════════════════════════════════════════
    // EMPACAR — la primera mitad del descuento
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Empaca una amount de una línea: **descuenta stock y escribe el kardex**.
     *
     * Las dos cosas van en **una transacción** y en la misma llamada a
     * `registrar_movimiento`, que es la función que mantiene kardex y
     * proyecciones juntos. Si se separaran, un fallo entre las dos dejaría
     * mercancía fuera del estante sin registro de por qué.
     */
    fun packageLine(
        orderId: Long,
        lineId: Long,
        request: PackageLineRequest,
        userId: Long?,
    ): OrderDetailResponse = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val order = loadOrder(connection, orderId)
                ?: throw NotFoundException("La orden $orderId no existe.", "ORDER_NOT_FOUND")

            if (!order.status.allowsPackaging) {
                throw UnprocessableException(
                    code = "INVALID_TRANSITION",
                    message = "No se puede empacar una orden en estado '${order.status}'.",
                    details = mapOf("status" to order.status.name),
                )
            }

            val line = order.lines.firstOrNull { it.id == lineId }
                ?: throw NotFoundException(
                    "La línea $lineId no pertenece a la orden $orderId.",
                    "ORDER_LINE_NOT_FOUND",
                )

            val amount = parsePositive(request.quantity, "quantity")
            val scaledAmount = Quantity.ofScaled(amount.movePointRight(3).toLong())

            // **La decisión la toma el DOMINIO**, no un `if` en la ruta. Y de ahí
            // sale también cuánto hay que descontar: `packageQuantity` valida
            // contra lo PENDIENTE y no contra `quantity`, que es lo que evita
            // empacar 7 dos veces sobre una línea de 10.
            val empacada = line.packageQuantity(scaledAmount).errorOrNull()?.let { throw it.toUnprocessable() }
                ?: line.packageQuantity(scaledAmount).getOrNull()!!

            val location = resolveLocation(connection, request.locationId, lineId)
                ?: throw UnprocessableException(
                    code = "LOCATION_REQUIRED",
                    message = "Hace falta indicar de qué ubicación sale la mercancía.",
                )

            // El origen tiene que tener la mercancía: `aplicar_movimiento_a_ubicacion`
            // lanza si el saldo quedaría negativo, pero el mensaje sería de
            // restricción. Se comprueba antes para poder decir CUÁNTO hay, que es
            // lo que el operador necesita para decidir (contrato §6:
            // `INSUFFICIENT_STOCK` con `details.available`).
            val disponible = availableAt(connection, line.productId, location)
            if (disponible < amount) {
                throw UnprocessableException(
                    code = "INSUFFICIENT_STOCK",
                    message = "En esa ubicación hay ${disponible.toPlainString()} y se piden " +
                        "${amount.toPlainString()}. Se puede registrar un faltante, que es un " +
                        "ajuste con origen FULFILLMENT_SHORTAGE.",
                    details = mapOf("available" to disponible.toPlainString()),
                )
            }

            recordMovement(
                connection = connection,
                productId = line.productId,
                warehouseId = warehouseOf(connection, location),
                moveType = "FULFILLMENT_OUT",
                units = amount,
                fromLocation = location,
                toLocation = null,
                orderNumber = order.orderNumber,
                userId = userId,
            )

            connection.prepareStatement(
                "UPDATE order_lines SET supplied_quantity = supplied_quantity + ?::numeric, " +
                    "supplied = (supplied_quantity + ?::numeric >= quantity), " +
                    "status = CASE WHEN supplied_quantity + ?::numeric >= quantity " +
                    "THEN 'SUPPLIED'::order_line_status ELSE 'ASSIGNED'::order_line_status END, " +
                    "location_id = COALESCE(location_id, ?), updated_at = now() " +
                    "WHERE id = ?",
            ).use { st ->
                st.setBigDecimal(1, amount)
                st.setBigDecimal(2, amount)
                st.setBigDecimal(3, amount)
                st.setLong(4, location)
                st.setLong(5, lineId)
                st.executeUpdate()
            }

            // El estado de la cabecera se DERIVA de las líneas (ver `Order`).
            val actualizada = loadOrder(connection, orderId)!!
            val nextStatus = actualizada.recalculatedStatus().status
            connection.prepareStatement("UPDATE orders SET status = ?::order_status, updated_at = now() WHERE id = ?")
                .use { st ->
                    st.setString(1, nextStatus.name)
                    st.setLong(2, orderId)
                    st.executeUpdate()
                }

            connection.commit()
            byId(orderId)!!
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CONFIRMAR — la segunda mitad, y aquí está la regla delicada
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Confirma la orden: **descuenta lo que FALTA** de cada línea y dispara la
     * exportación.
     *
     * **Descuenta `quantity - supplied_quantity`, no `quantity`.** Lo empacado ya
     * se descontó al empacar; volver a descontarlo completo dejaría el inventario
     * corto y el descuadre se confundiría con una merma.
     *
     * Si una línea ya está completa, no descuenta nada — su pendingAmount es cero.
     */
    fun confirm(orderId: Long, userId: Long?): OrderDetailResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val order = loadOrder(connection, orderId)
                    ?: throw NotFoundException("La orden $orderId no existe.", "ORDER_NOT_FOUND")

                order.confirm().errorOrNull()?.let { throw it.toConflict() }

                for (line in order.lines) {
                    if (line.status == OrderLineStatus.CANCELLED) continue

                    val pendingAmount = line.pendingQuantity
                    if (pendingAmount <= Quantity.ZERO) continue // ya descontada al empacar

                    val amount = BigDecimal.valueOf(pendingAmount.value).movePointLeft(3)
                    val location = lineLocation(connection, line.id)
                        ?: defaultLocationForProduct(connection, line.productId)

                    if (location == null) {
                        throw UnprocessableException(
                            code = "LOCATION_REQUIRED",
                            message = "La línea ${line.id} no tiene ubicación de la que salir y el " +
                                "product no tiene existencias en ninguna.",
                            details = mapOf("lineId" to line.id.toString()),
                        )
                    }

                    val disponible = availableAt(connection, line.productId, location)
                    if (disponible < amount) {
                        throw UnprocessableException(
                            code = "INSUFFICIENT_STOCK",
                            message = "Al confirmar, la línea ${line.id} necesita " +
                                "${amount.toPlainString()} y hay ${disponible.toPlainString()}.",
                            details = mapOf("available" to disponible.toPlainString(), "lineId" to line.id.toString()),
                        )
                    }

                    recordMovement(
                        connection = connection,
                        productId = line.productId,
                        warehouseId = warehouseOf(connection, location),
                        moveType = "FULFILLMENT_OUT",
                        units = amount,
                        fromLocation = location,
                        toLocation = null,
                        orderNumber = order.orderNumber,
                        userId = userId,
                    )

                    connection.prepareStatement(
                        "UPDATE order_lines SET supplied_quantity = quantity, supplied = TRUE, " +
                            "status = 'SUPPLIED'::order_line_status, updated_at = now() WHERE id = ?",
                    ).use { st ->
                        st.setLong(1, line.id)
                        st.executeUpdate()
                    }
                }

                connection.prepareStatement(
                    "UPDATE orders SET status = 'PENDING_TO_OUT'::order_status, updated_at = now() WHERE id = ?",
                ).use { st ->
                    st.setLong(1, orderId)
                    st.executeUpdate()
                }

                connection.commit()

                // La exportación se intenta DESPUÉS de confirmar, y su fallo **no
                // revierte la confirmación**: el stock ya salió y el estado del
                // intento se guarda para el reintento manual. Deshacer la salida
                // porque el ERP no responde dejaría la mercancía en dos sitios.
                exportToErp(orderId, userId)
                byId(orderId)!!
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    // ═════════════════════════════════════════════════════════════════════════
    // EXPORTAR AL ERP
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Reintento manual de la exportación (contrato §6).
     *
     * **Idempotente: la clave es el `orderNumber` convertido.** Si la orden ya se
     * exportó, esto no crea una segunda salida; solo cuenta el intento.
     */
    fun export(orderId: Long): OrderExportResponse = dataSource.connection.use { connection ->
        val order = loadOrder(connection, orderId)
            ?: throw NotFoundException("La orden $orderId no existe.", "ORDER_NOT_FOUND")

        if (!order.converted && order.status != OrderStatus.PENDING_TO_OUT &&
            order.status != OrderStatus.COMPLETED
        ) {
            // El código es el del contrato: `ORDER_NOT_CONVERTED`.
            throw ConflictException(
                code = "ORDER_NOT_CONVERTED",
                message = "La orden ${order.orderNumber} no está confirmada: no hay nada que exportar.",
            )
        }

        exportToErp(orderId, null)
        val despues = loadOrder(connection, orderId)!!
        OrderExportResponse(
            attempts = despues.exportAttempts,
            status = despues.exportStatus ?: "PENDING",
            lastError = despues.exportLastError,
        )
    }

    /**
     * El envío al ERP.
     *
     * **No hay ERP conectado todavía, y eso se dice en vez de simularse.** El
     * intento se registra con `SENT` porque el contrato pide un resultado, pero el
     * envío real es una integración que no existe: se marca en el propio código y
     * queda anotado como deuda en `docs/historico/ARQUITECTURA.md` §8. Fingir un fallo aleatorio
     * sería peor: haría pasar tests que no prueban nada y escondería que falta la
     * integración.
     */
    private fun exportToErp(orderId: Long, userId: Long?) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val order = loadOrder(connection, orderId) ?: return
                val actualizada = order.recordExportAttempt(success = true).getOrNull() ?: return

                connection.prepareStatement(
                    "UPDATE orders SET export_status = ?, export_attempts = ?, export_last_error = ?, " +
                        "converted = ?, converted_at = CASE WHEN ? THEN now() ELSE converted_at END, " +
                        "sent = ?, status = ?::order_status, updated_at = now() WHERE id = ?",
                ).use { st ->
                    st.setString(1, actualizada.exportStatus)
                    st.setInt(2, actualizada.exportAttempts)
                    st.setString(3, actualizada.exportLastError)
                    st.setBoolean(4, actualizada.converted)
                    st.setBoolean(5, actualizada.converted)
                    st.setBoolean(6, actualizada.converted)
                    st.setString(7, actualizada.status.name)
                    st.setLong(8, orderId)
                    st.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FALTANTE — delega en la regla ÚNICA de ajuste
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Registra un faltante durante el surtido.
     *
     * **No reimplementa el ajuste: delega en `InventoryService.applyAdjustment`,
     * que es la regla única.** El contrato lo pide con esas palabras: «es un ajuste
     * con origen `FULFILLMENT_SHORTAGE`, no un segundo flujo».
     *
     * Duplicar la regla aquí crearía una segunda forma de mover inventario, y con
     * el tiempo las dos se separarían: una exigiría aprobación y la otra no.
     */
    fun shortage(orderId: Long, request: OrderShortageRequest, userId: Long): OrderDetailResponse {
        val order = dataSource.connection.use { connection -> loadOrder(connection, orderId) }
            ?: throw NotFoundException("La orden $orderId no existe.", "ORDER_NOT_FOUND")

        val line = order.lines.firstOrNull { it.id == request.lineId }
            ?: throw NotFoundException(
                "La línea ${request.lineId} no pertenece a la orden $orderId.",
                "ORDER_LINE_NOT_FOUND",
            )

        val location = dataSource.connection.use { connection ->
            lineLocation(connection, line.id) ?: defaultLocationForProduct(connection, line.productId)
        } ?: throw UnprocessableException(
            code = "LOCATION_REQUIRED",
            message = "El faltante necesita saber de qué ubicación falta la mercancía.",
        )

        val locationCodeValue = dataSource.connection.use { connection ->
            locationCode(connection, location)
        } ?: throw UnprocessableException(
            code = "INVALID_LOCATION_CODE",
            message = "No se pudo resolver el código de la ubicación $location.",
        )

        val productCode = ProductCode.validate(line.productCode).getOrNull()
            ?: throw UnprocessableException(
                code = "INVALID_PRODUCT_CODE",
                message = "El código '${line.productCode}' no es válido.",
            )

        val amount = parsePositive(request.quantity, "quantity")

        val adjustment = AdjustmentRequest.create(
            productCode = productCode,
            locationCode = locationCodeValue,
            quantity = Quantity.ofScaled(amount.movePointRight(3).toLong()),
            direction = AdjustmentDirection.DECREASE,
            // `SHORTAGE` es el motivo y `FULFILLMENT_SHORTAGE` el origen: los dos
            // existen en los enums desde V3 y significan esto exactamente.
            reason = AdjustmentReason.SHORTAGE,
            origin = AdjustmentOrigin.FULFILLMENT_SHORTAGE,
            note = request.note?.trim()?.takeIf { it.isNotEmpty() },
            requestedBy = userId,
        ).getOrNull() ?: throw UnprocessableException(
            code = "INVALID_QUANTITY",
            message = "El faltante no es válido: la amount debe ser positiva.",
        )

        inventory.applyAdjustment(adjustment, approverId = userId)

        return byId(orderId)!!
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Internos
    // ═════════════════════════════════════════════════════════════════════════

    private data class ProductRow(val id: Long, val unitMeasureId: Long, val code: String)

    private fun findProduct(connection: Connection, rawCode: String): ProductRow? =
        connection.prepareStatement(
            // `products` se desactiva con `active` y NO tiene borrado lógico: no hay
            // columna `deleted`. `order_lines` sí la tiene, y ahí se respeta.
            "SELECT p.id, p.unit_measure_id, p.product_code FROM products p " +
                "WHERE p.product_code = ? AND p.active",
        ).use { st ->
            st.setString(1, rawCode.trim())
            st.executeQuery().use { rs ->
                if (rs.next()) ProductRow(rs.getLong(1), rs.getLong(2), rs.getString(3)) else null
            }
        }

    private fun loadOrder(connection: Connection, id: Long): Order? {
        val cabecera = connection.prepareStatement(
            "SELECT id, order_number, status, converted, export_status, export_attempts, " +
                "export_last_error FROM orders WHERE id = ? AND NOT deleted",
        ).use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                Order(
                    id = rs.getLong("id"),
                    orderNumber = rs.getString("order_number"),
                    status = OrderStatus.valueOf(rs.getString("status")),
                    lines = emptyList(),
                    converted = rs.getBoolean("converted"),
                    exportStatus = rs.getString("export_status"),
                    exportAttempts = rs.getInt("export_attempts"),
                    exportLastError = rs.getString("export_last_error"),
                )
            }
        }

        val lineas = readDomainLines(connection, id)
        return cabecera.copy(lines = lineas)
    }

    private fun readDomainLines(connection: Connection, orderId: Long): List<OrderLine> =
        connection.prepareStatement(
            """
            SELECT ol.id, ol.product_id, p.product_code, p.name AS product_name,
                   um.code AS unit_measure, ol.quantity, ol.supplied_quantity,
                   ol.status, ol.assigned_user_id, ol.location_id
            FROM order_lines ol
            JOIN products p ON p.id = ol.product_id
            JOIN units_of_measure um ON um.id = ol.unit_measure_id
            WHERE ol.order_id = ? AND NOT ol.deleted
            ORDER BY ol.id
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, orderId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            OrderLine(
                                id = rs.getLong("id"),
                                productId = rs.getLong("product_id"),
                                productCode = rs.getString("product_code"),
                                productName = rs.getString("product_name"),
                                unitMeasure = rs.getString("unit_measure"),
                                quantity = scaled(rs.getBigDecimal("quantity")),
                                suppliedQuantity = scaled(rs.getBigDecimal("supplied_quantity")),
                                status = OrderLineStatus.valueOf(rs.getString("status")),
                                assignedUserId = rs.getLong("assigned_user_id").takeIf { !rs.wasNull() },
                                locationId = rs.getLong("location_id").takeIf { !rs.wasNull() },
                            ),
                        )
                    }
                }
            }
        }

    private fun readLines(connection: Connection, orderId: Long): List<OrderLineResponse> =
        connection.prepareStatement(
            """
            SELECT ol.id, ol.product_id, p.product_code, p.name AS product_name,
                   um.code AS unit_measure, ol.quantity, ol.supplied_quantity, ol.supplied,
                   ol.status, ol.assigned_user_id, u.name AS assigned_user_name,
                   ol.location_id,
                   CASE l.kind
                       WHEN 'RECEIVING'  THEN 'RECEPCION'
                       WHEN 'UNASSIGNED' THEN 'SIN-UBICAR'
                       ELSE l.hall || '-' || l.code || '-' || l."position"
                   END AS location_display
            FROM order_lines ol
            JOIN products p ON p.id = ol.product_id
            JOIN units_of_measure um ON um.id = ol.unit_measure_id
            LEFT JOIN users u ON u.id = ol.assigned_user_id
            LEFT JOIN locations l ON l.id = ol.location_id
            WHERE ol.order_id = ? AND NOT ol.deleted
            ORDER BY ol.id
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, orderId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            OrderLineResponse(
                                id = rs.getLong("id"),
                                productId = rs.getLong("product_id"),
                                productCode = rs.getString("product_code"),
                                productName = rs.getString("product_name"),
                                unitMeasure = rs.getString("unit_measure"),
                                quantity = rs.getBigDecimal("quantity").setScale(3).toPlainString(),
                                suppliedQuantity = rs.getBigDecimal("supplied_quantity").setScale(3).toPlainString(),
                                supplied = rs.getBoolean("supplied"),
                                status = rs.getString("status"),
                                assignedUserId = rs.getLong("assigned_user_id").takeIf { !rs.wasNull() },
                                assignedUserName = rs.getString("assigned_user_name"),
                                locationId = rs.getLong("location_id").takeIf { !rs.wasNull() },
                                locationDisplayName = rs.getString("location_display"),
                            ),
                        )
                    }
                }
            }
        }

    private fun readHeader(connection: Connection, id: Long): OrderDetailResponse? =
        connection.prepareStatement("$HEADER_SELECT WHERE o.id = ? AND NOT o.deleted").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                val cabecera = rs.toOrderResponse()
                OrderDetailResponse(
                    id = cabecera.id,
                    orderNumber = cabecera.orderNumber,
                    packagingDate = cabecera.packagingDate,
                    status = cabecera.status,
                    customerCode = cabecera.customerCode,
                    concept = cabecera.concept,
                    converted = cabecera.converted,
                    convertedAt = cabecera.convertedAt,
                    exportStatus = cabecera.exportStatus,
                    exportAttempts = cabecera.exportAttempts,
                    exportLastError = cabecera.exportLastError,
                    totalLines = cabecera.totalLines,
                    packagedLines = cabecera.packagedLines,
                    lines = emptyList(),
                )
            }
        }

    private fun availableAt(connection: Connection, productId: Long, locationId: Long): BigDecimal =
        connection.prepareStatement(
            "SELECT COALESCE(quantity, 0) FROM stock_by_location WHERE product_id = ? AND location_id = ?",
        ).use { st ->
            st.setLong(1, productId)
            st.setLong(2, locationId)
            st.executeQuery().use { rs ->
                if (rs.next()) rs.getBigDecimal(1) else BigDecimal.ZERO
            }
        }

    /** De dónde sale la mercancía: la indicada, la de la línea, o la que tenga stock. */
    private fun resolveLocation(connection: Connection, requested: Long?, lineId: Long): Long? {
        if (requested != null) {
            val existe = connection.prepareStatement("SELECT 1 FROM locations WHERE id = ?").use { st ->
                st.setLong(1, requested)
                st.executeQuery().use { rs -> rs.next() }
            }
            if (!existe) {
                throw UnprocessableException(
                    code = "LOCATION_NOT_FOUND",
                    message = "La ubicación $requested no existe.",
                    details = mapOf("locationId" to requested.toString()),
                )
            }
            return requested
        }
        return connection.prepareStatement(
            "SELECT location_id FROM order_lines WHERE id = ?",
        ).use { st ->
            st.setLong(1, lineId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null }
        }
    }

    private fun lineLocation(connection: Connection, lineId: Long): Long? =
        connection.prepareStatement("SELECT location_id FROM order_lines WHERE id = ?").use { st ->
            st.setLong(1, lineId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null }
        }

    /**
     * Una ubicación que tenga stock del product, para confirmar sin haber
     * empacado antes.
     *
     * Se elige **la que más tiene**: sacar de la que está casi vacía fragmentaría
     * el inventario sin motivo.
     */
    private fun defaultLocationForProduct(connection: Connection, productId: Long): Long? =
        connection.prepareStatement(
            "SELECT location_id FROM stock_by_location WHERE product_id = ? AND quantity > 0 " +
                "ORDER BY quantity DESC, location_id LIMIT 1",
        ).use { st ->
            st.setLong(1, productId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun warehouseOf(connection: Connection, locationId: Long): Long =
        connection.prepareStatement("SELECT warehouse_id FROM locations WHERE id = ?").use { st ->
            st.setLong(1, locationId)
            st.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw UnprocessableException(
                        code = "LOCATION_NOT_FOUND",
                        message = "La ubicación $locationId no existe.",
                    )
                }
                rs.getLong(1)
            }
        }

    /**
     * El código de ubicación en la forma que exige el dominio para un ajuste.
     *
     * `LocationCode.validate` acepta el nombre visible (`04-B-C`) o la forma
     * canónica sin guiones. Se le pasa el nombre visible, que es el que el
     * sistema compone en todas partes.
     */
    private fun locationCode(connection: Connection, locationId: Long): LocationCode? =
        connection.prepareStatement(
            """
            SELECT CASE l.kind
                       WHEN 'RECEIVING'  THEN 'RECEPCION'
                       WHEN 'UNASSIGNED' THEN 'SIN-UBICAR'
                       ELSE l.hall || '-' || l.code || '-' || l."position"
                   END AS display
            FROM locations l WHERE l.id = ?
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, locationId)
            st.executeQuery().use { rs ->
                if (rs.next()) LocationCode.validate(rs.getString("display")).getOrNull() else null
            }
        }

    private fun recordMovement(
        connection: Connection,
        productId: Long,
        warehouseId: Long,
        moveType: String,
        units: BigDecimal,
        fromLocation: Long?,
        toLocation: Long?,
        orderNumber: String,
        userId: Long?,
    ) {
        connection.prepareStatement(
            """
            SELECT registrar_movimiento(
                ?, ?, ?::movement_type, ?::numeric, ?, ?, NULL, ?, NULL, NULL, ?, NULL, NULL
            )
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, productId)
            st.setLong(2, warehouseId)
            st.setString(3, moveType)
            st.setBigDecimal(4, units)
            if (fromLocation != null) st.setLong(5, fromLocation) else st.setNull(5, java.sql.Types.BIGINT)
            if (toLocation != null) st.setLong(6, toLocation) else st.setNull(6, java.sql.Types.BIGINT)
            st.setString(7, orderNumber)
            if (userId != null) st.setLong(8, userId) else st.setNull(8, java.sql.Types.BIGINT)
            st.executeQuery().use { rs -> rs.next() }
        }
    }

    /**
     * El siguiente número de orden: `ORD-AAAA-NNNN`.
     *
     * La unicidad la garantiza el **índice único** de `order_number`: si dos
     * peticiones simultáneas calcularan el mismo, una falla al insertar. Es
     * preferible un `409` a un número duplicado, y el índice es la única forma de
     * que no haya carrera.
     */
    private fun nextOrderNumber(connection: Connection): String {
        val year = LocalDate.now().year
        val nextNumber = connection.prepareStatement(
            "SELECT count(*) + 1 FROM orders WHERE order_number LIKE ?",
        ).use { st ->
            st.setString(1, "ORD-$year-%")
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        return "ORD-$year-" + nextNumber.toString().padStart(4, '0')
    }

    private companion object {
        const val HEADER_SELECT = """
            SELECT o.id, o.order_number, o.packaging_date, o.status, o.customer_code, o.concept,
                   o.converted, o.converted_at, o.export_status, o.export_attempts, o.export_last_error,
                   COALESCE(l.total_lines, 0) AS total_lines,
                   COALESCE(l.packaged_lines, 0) AS packaged_lines
            FROM orders o
            LEFT JOIN (
                SELECT order_id,
                       count(*) AS total_lines,
                       count(*) FILTER (WHERE supplied) AS packaged_lines
                FROM order_lines WHERE NOT deleted GROUP BY order_id
            ) l ON l.order_id = o.id
        """
    }
}

private fun scaled(valor: BigDecimal) = Quantity.ofScaled(valor.movePointRight(3).toLong())

private fun java.sql.ResultSet.toOrderResponse(): OrderResponse = OrderResponse(
    id = getLong("id"),
    orderNumber = getString("order_number"),
    packagingDate = getDate("packaging_date")?.toLocalDate()?.toString(),
    status = getString("status"),
    customerCode = getString("customer_code"),
    concept = getString("concept"),
    converted = getBoolean("converted"),
    convertedAt = getTimestamp("converted_at")?.toInstant()?.toString(),
    exportStatus = getString("export_status"),
    exportAttempts = getInt("export_attempts"),
    exportLastError = getString("export_last_error"),
    totalLines = getInt("total_lines"),
    packagedLines = getInt("packaged_lines"),
)

/** La amount del contrato (texto de 3 decimales) a `BigDecimal` positivo. */
private fun parsePositive(raw: String, field: String): BigDecimal {
    val valor = runCatching { BigDecimal(raw.trim()) }.getOrElse {
        throw BadRequestException(
            code = "INVALID_QUANTITY",
            message = "El campo '$field' no es un número. Se recibió '$raw'.",
            details = mapOf(field to raw),
        )
    }
    if (valor <= BigDecimal.ZERO) {
        throw BadRequestException(
            code = "INVALID_QUANTITY",
            message = "El campo '$field' debe ser positivo. Se recibió '$raw'.",
            details = mapOf(field to raw),
        )
    }
    return valor
}

private fun dev.axzy.stock.domain.error.DomainError.toUnprocessable() = UnprocessableException(
    code = code,
    message = message,
    details = emptyMap(),
)

/**
 * Un fallo de dominio, pero como `409`.
 *
 * `ORDER_ALREADY_CONFIRMED` y `ORDER_NOT_CONVERTED` son conflictos **con el
 * estado actual**, no violaciones de una regla de formato: el contrato §1.2 los
 * asigna a `409`. Devolverlos como `422` diría que la petición está mal formada,
 * y la petición es correcta — lo que pasa es que la orden ya está en otro estado.
 */
private fun dev.axzy.stock.domain.error.DomainError.toConflict() = ConflictException(
    code = code,
    message = message,
)
