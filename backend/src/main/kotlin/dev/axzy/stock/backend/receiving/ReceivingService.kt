package dev.axzy.stock.backend.receiving

import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.Quantity
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SERVICIO DE RECEPCIÓN — FACTURAS Y LA REGLA B1
 *
 * La recepción es el flujo que el legacy tenía peor: una línea con discrepancia se
 * podía ubicar sin que nadie la aprobara, así que la diferencia entre lo facturado y
 * lo contado **se perdía en el inventario**. Aquí la regla B1 lo impide con dos
 * barreras:
 *
 *  1. La **base de datos** tiene un `CHECK` que hace imposible una línea `APPROVED`
 *     sin `approved_by` ni sin motivo. No depende de que el código se acuerde.
 *  2. El **servicio** rechaza ubicar una línea en discrepancia, con un código de
 *     error que le dice al usuario qué hacer.
 *
 * **La aprobación es por FACTURA, no por línea** (decisión R-86). Aprobar línea a
 * línea obliga al supervisor a repetir el mismo juicio N veces y —lo importante—
 * **invita a aprobar lo propio sin darse cuenta**. Con una sola acción sobre el
 * documento, la segregación de funciones es evidente.
 *
 * Referencia: `PLAN_ALMACEN_INTEGRAL.md` §5 y las decisiones B1, R-74 y R-86.
 * ═══════════════════════════════════════════════════════════════════════════
 */
/**
 * @param audit el escritor de auditoría. **Se recibe y no se construye aquí**, para que
 *   haya **una sola instancia** en toda la aplicación (ver [dev.axzy.stock.backend.config.AppDependencies]).
 *
 *   El valor por defecto existe para no romper a quien instancia el servicio sin
 *   contenedor —los tests, sobre todo—, pero **la aplicación siempre pasa el suyo**:
 *   si cada servicio construyera el suyo, habría tantas instancias como servicios y el
 *   singleton sería una ilusión.
 */
class ReceivingService(
    private val dataSource: DataSource,
    private val audit: dev.axzy.stock.backend.audit.AuditWriter =
        dev.axzy.stock.backend.audit.AuditWriter(dataSource),
) {


    // ═══════════════════════════════════════════════════════════════════════
    // FACTURAS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Crea una factura con sus líneas.
     *
     * Se hace en UNA transacción: una factura sin líneas es un documento que no
     * sirve para nada y que bloquea el flujo de recepción.
     */
    fun createInvoice(
        number: String,
        date: LocalDate,
        type: String,
        lines: List<NewInvoiceLine>,
        userId: Long,
    ): Long = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val existing = connection.prepareStatement(
                "SELECT id FROM invoices WHERE invoice_number = ?",
            ).use { st ->
                st.setString(1, number)
                st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
            }

            if (existing != null) {
                throw ConflictException(
                    "DUPLICATE_INVOICE_NUMBER",
                    "Ya existe una factura con el número '$number'.",
                    mapOf("invoiceId" to existing.toString()),
                )
            }

            val invoiceId = connection.prepareStatement(
                """
                INSERT INTO invoices (invoice_number, receipt_date, invoice_type, status, concept)
                VALUES (?, ?, ?::invoice_type, 'PENDING'::invoice_status, ?)
                RETURNING id
                """.trimIndent(),
            ).use { st ->
                st.setString(1, number)
                st.setDate(2, java.sql.Date.valueOf(date))
                st.setString(3, type)
                st.setString(4, null)
                st.executeQuery().use { rs -> rs.next(); rs.getLong("id") }
            }

            lines.forEach { line ->
                val productId = connection.prepareStatement(
                    "SELECT id FROM products WHERE product_code = ? AND active",
                ).use { st ->
                    st.setString(1, line.productCode)
                    st.executeQuery().use { rs ->
                        if (!rs.next()) {
                            throw UnprocessableException(
                                "PRODUCT_NOT_FOUND",
                                "El producto '${line.productCode}' no existe en el catálogo.",
                            )
                        }
                        rs.getLong("id")
                    }
                }

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
                    st.setBigDecimal(4, line.quantity.toBigDecimal())
                    st.setBigDecimal(5, line.boxQuantity.toBigDecimal())
                    st.setBigDecimal(6, line.unitsPerBox?.toBigDecimal())
                    st.setString(7, line.unitMeasure)
                    st.executeUpdate()
                }
            }

            connection.commit()
            invoiceId
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        }
    }

    /** Lee una factura con sus líneas. */
    fun invoice(id: Long): InvoiceDetail = dataSource.connection.use { connection ->
        val header = connection.prepareStatement(
            """
            SELECT i.id, i.invoice_number, i.receipt_date, i.invoice_type, i.status,
                   i.customer_code, i.concept, i.created_at
            FROM invoices i WHERE i.id = ? AND NOT i.deleted
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw NotFoundException("La factura $id no existe.", "INVOICE_NOT_FOUND")
                }
                InvoiceDetail(
                    id = rs.getLong("id"),
                    invoiceNumber = rs.getString("invoice_number"),
                    receiptDate = rs.getDate("receipt_date").toLocalDate().toString(),
                    invoiceType = rs.getString("invoice_type"),
                    status = rs.getString("status"),
                    customerCode = rs.getString("customer_code"),
                    concept = rs.getString("concept"),
                    totalLines = 0,
                    pendingApprovalLines = 0,
                    lines = readLines(connection, id),
                )
            }
        }

        header.copy(
            totalLines = header.lines.size,
            pendingApprovalLines = header.lines.count {
                it.status == InvoiceLineStatus.DISCREPANCY.name
            },
        )
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
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            rs.toInvoiceLineDto(),
                        )
                    }
                }
            }
        }

    // ═══════════════════════════════════════════════════════════════════════
    // CAPTURA FÍSICA
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Registra el conteo físico de una línea.
     *
     * La `difference` la calcula **la base**, con su columna generada, así que no
     * puede desincronizarse. Aquí solo se decide el estado a partir de ella.
     */
    fun captureLine(
        invoiceId: Long,
        lineId: Long,
        quantityPhysical: Quantity,
        boxesPhysical: Quantity?,
        userId: Long,
    ): InvoiceLineDto = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val line = readLineFor(connection, invoiceId, lineId)

            if (line.status == InvoiceLineStatus.APPROVED.name ||
                line.status == InvoiceLineStatus.LOCATED.name
            ) {
                throw ConflictException(
                    "LINE_ALREADY_RESOLVED",
                    "La línea ya está ${line.status.lowercase()} y no se puede volver a capturar.",
                )
            }

            val quantityInvoiced = BigDecimal(line.quantity)
            val physical = quantityPhysical.toBigDecimal()

            // El estado lo decide la DIFERENCIA, y se lee de la base después de
            // escribir: calcularla aquí sería duplicar la regla y las dos versiones
            // se separarían.
            val newStatus = if (physical.compareTo(quantityInvoiced) == 0) {
                InvoiceLineStatus.CAPTURED.name
            } else {
                InvoiceLineStatus.DISCREPANCY.name
            }

            connection.prepareStatement(
                """
                UPDATE invoice_lines
                SET physical_quantity = ?, physical_box_quantity = ?,
                    status = ?::invoice_line_status,
                    captured_by = ?, captured_at = now(), updated_at = now()
                WHERE id = ? AND invoice_id = ?
                """.trimIndent(),
            ).use { st ->
                st.setBigDecimal(1, physical)
                st.setBigDecimal(2, boxesPhysical?.toBigDecimal())
                st.setString(3, newStatus)
                st.setLong(4, userId)
                st.setLong(5, lineId)
                st.setLong(6, invoiceId)
                st.executeUpdate()
            }

            // El estado de la factura se deriva del de sus líneas, no se mantiene a
            // mano: una factura con una línea en discrepancia está IN_PROGRESS, no
            // COMPLETED.
            refreshInvoiceStatus(connection, invoiceId)

            // ── AUDITORÍA DEL CONTEO FÍSICO ─────────────────────────────────
            //
            // **El conteo es el dato del que depende toda la discrepancia.** Si no
            // queda registrado quién contó, cuándo y con qué resultado, aprobar una
            // diferencia se convierte en un acto de fe: no se puede comprobar si la
            // persona que contó tenía la formación, ni si contó cinco facturas
            // seguidas a las tres de la mañana.
            //
            // `previousState` y `newState` llevan las CANTIDADES, no solo el estado:
            // «pasó de PENDING a DISCREPANCY» no dice nada; «contó 90 donde decía 100»
            // es lo que permite investigar.
            audit.record(
                connection = connection,
                actorUserId = userId,
                actorUsername = null,
                channel = dev.axzy.stock.backend.audit.AuditWriter.Channel.WEB,
                entityType = "invoice_line",
                entityId = lineId.toString(),
                entityLabel = "invoice #$invoiceId",
                action = "invoice.line.capture",
                level = dev.axzy.stock.backend.audit.AuditWriter.Level.RELEVANT,
                previousState = """{"status":"PENDING","invoiced":"${line.quantity}"}""",
                newState = """{"status":"$newStatus","physical":"${java.math.BigDecimal.valueOf(quantityPhysical.value).movePointLeft(dev.axzy.stock.domain.value.Quantity.SCALE).toPlainString()}"}""",
                permissionKey = "invoice.capture",
                decision = dev.axzy.stock.backend.audit.AuditWriter.Decision.ALLOWED,
                result = dev.axzy.stock.backend.audit.AuditWriter.Result.SUCCESS,
                businessDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC),
            )

            connection.commit()

            // Se responde con la línea COMPLETA leída de `leerLineas`, no con el
            // resumen interno: el cliente necesita ver la `difference` que calculó
            // la base para saber si tiene que aprobar algo.
            readLines(connection, invoiceId).first { it.id == lineId }
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LA REGLA B1 — APROBACIÓN POR FACTURA
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Aprueba TODAS las líneas en discrepancia de una factura.
     *
     * **Por factura y no por línea** (R-86). Y con dos comprobaciones que son el
     * control interno:
     *
     *  · El motivo es obligatorio.
     *  · **Quien aprueba no puede ser quien capturó ninguna de las líneas
     *    pendientes** (R-74). El error dice CUÁLES, para que el supervisor sepa qué
     *    reasignar.
     */
    fun approveInvoice(
        invoiceId: Long,
        reason: String,
        note: String?,
        approverId: Long,
    ): InvoiceDetail = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val pending = connection.prepareStatement(
                """
                SELECT l.id, l.captured_by
                FROM invoice_lines l
                WHERE l.invoice_id = ?
                  AND l.status = 'DISCREPANCY'::invoice_line_status
                  AND NOT l.deleted
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, invoiceId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getLong("id") to rs.getLong("captured_by").takeIf { !rs.wasNull() })
                        }
                    }
                }
            }

            if (pending.isEmpty()) {
                throw ConflictException(
                    "NO_PENDING_APPROVAL",
                    "La factura no tiene líneas en discrepancia que aprobar. " +
                        "Solo se aprueban las líneas con diferencia respecto a lo facturado.",
                )
            }

            // ── Segregación de funciones ────────────────────────────────────
            val own = pending.filter { it.second == approverId }.map { it.first }
            if (own.isNotEmpty()) {
                throw UnprocessableException(
                    "SEGREGATION_OF_DUTIES",
                    "No puedes aprobar una discrepancia que tú mismo capturaste. " +
                        "Las líneas ${own.joinToString(", ")} las contó el usuario " +
                        "$approverId, así que tiene que aprobarlas otra persona.",
                    mapOf("lines" to own.joinToString(",")),
                )
            }

            if (reason.isBlank()) {
                throw UnprocessableException(
                    "REASON_REQUIRED",
                    "Aprobar una discrepancia exige un motivo: dentro de un año nadie " +
                        "podrá explicar por qué se aceptó la diferencia.",
                )
            }

            connection.prepareStatement(
                """
                UPDATE invoice_lines
                SET status = 'APPROVED'::invoice_line_status,
                    approved_by = ?, approved_at = now(),
                    adjustment_reason = ?::adjustment_reason,
                    updated_at = now()
                WHERE invoice_id = ?
                  AND status = 'DISCREPANCY'::invoice_line_status
                  AND NOT deleted
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, approverId)
                st.setString(2, reason.uppercase())
                st.setLong(3, invoiceId)
                st.executeUpdate()
            }

            refreshInvoiceStatus(connection, invoiceId)

            // ── LA AUDITORÍA MÁS IMPORTANTE DEL SISTEMA ─────────────────────
            //
            // **Aprobar una discrepancia es el control interno central** (decisión
            // R-74): es la acción que acepta que lo recibido no coincide con lo
            // facturado. El registro tiene que permitir responder **«¿quién aceptó
            // esta diferencia, cuándo y por qué?»** dentro de un año, cuando la
            // diferencia ya no se pueda reconstruir de otra forma.
            //
            // Va en la MISMA transacción y antes del commit: si la aprobación se
            // aplicara y su registro no, la diferencia quedaría aceptada **sin
            // constancia de quién la autorizó**, que es exactamente el agujero que
            // esta funcionalidad existe para cerrar.
            //
            // El `newState` lleva el MOTIVO además del estado: un registro que solo
            // dijera «pasó a APPROVED» obligaría a cruzar con la tabla de líneas para
            // saber por qué se aceptó, y esa tabla puede cambiar. La auditoría tiene
            // que bastarse sola.
            audit.record(
                connection = connection,
                actorUserId = approverId,
                actorUsername = null,
                channel = dev.axzy.stock.backend.audit.AuditWriter.Channel.WEB,
                entityType = "invoice",
                entityId = invoiceId.toString(),
                action = "invoice.discrepancy.approve",
                level = dev.axzy.stock.backend.audit.AuditWriter.Level.RELEVANT,
                previousState = """{"status":"DISCREPANCY","pendingLines":${pending.size}}""",
                newState = """{"status":"APPROVED","approvedLines":${pending.size},"adjustmentReason":"$reason"}""",
                reason = reason,
                note = note,
                permissionKey = "invoice.approve",
                decision = dev.axzy.stock.backend.audit.AuditWriter.Decision.ALLOWED,
                result = dev.axzy.stock.backend.audit.AuditWriter.Result.SUCCESS,
                businessDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC),
            )

            connection.commit()
            invoice(invoiceId)
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        }
    }

    /**
     * Comprueba que una línea se puede ubicar. **La regla B1.**
     *
     * Se expone aparte porque la usa el flujo de ubicación: una línea en
     * discrepancia **no se puede ubicar** hasta que un supervisor la apruebe.
     *
     * @throws ConflictException con `DISCREPANCY_NOT_APPROVED` si está bloqueada.
     */
    fun requireLocatable(connection: Connection, lineId: Long) {
        val status = connection.prepareStatement(
            "SELECT status FROM invoice_lines WHERE id = ? AND NOT deleted",
        ).use { st ->
            st.setLong(1, lineId)
            st.executeQuery().use { rs ->
                if (!rs.next()) throw NotFoundException("La línea $lineId no existe.", "LINE_NOT_FOUND")
                rs.getString("status")
            }
        }

        // El estado del dominio es DISCREPANCY: es el mismo concepto que
        // PENDING_APPROVAL del contrato HTTP. El contrato usa un nombre y el
        // dominio otro, y tenerlo claro evita inventar un estado que no existe.
        if (status == InvoiceLineStatus.DISCREPANCY.name) {
            throw ConflictException(
                "DISCREPANCY_NOT_APPROVED",
                "La línea tiene una discrepancia sin aprobar y no se puede ubicar. " +
                    "La diferencia entre lo facturado y lo contado tiene que resolverla " +
                    "un supervisor antes: si se ubicara, la diferencia desaparecería del " +
                    "inventario sin que nadie la autorice.",
            )
        }
    }

    /**
     * ══════════════════════════════════════════════════════════════════════
     * RECHAZAR UNA DISCREPANCIA — la otra salida de la regla B1
     *
     * **Aprobar y rechazar son la misma decisión tomada al revés:**
     *
     *  · **Aprobar** = la diferencia se ACEPTA. La mercancía se queda y el motivo
     *    explica por qué falta o sobra.
     *  · **Rechazar** = la diferencia NO se acepta. La línea se **cancela**: la
     *    mercancía no entra al inventario porque no se acepta tal como llegó.
     *
     * **Sin esta salida, la única opción ante una discrepancia era aceptarla**, y eso
     * convierte el control en un trámite: un supervisor que no puede rechazar acaba
     * aprobando para desbloquear la factura. **El rechazo es lo que da sentido a la
     * aprobación.**
     * ══════════════════════════════════════════════════════════════════════
     */
    fun rejectDiscrepancy(
        invoiceId: Long,
        reason: String,
        note: String?,
        rejectedBy: Long,
    ): InvoiceDetail = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val pending = pendingDiscrepancyLines(connection, invoiceId)

            if (pending.isEmpty()) {
                throw ConflictException(
                    "NO_PENDING_APPROVAL",
                    "La factura no tiene líneas en discrepancia que rechazar. " +
                        "Solo se rechazan las líneas con diferencia respecto a lo facturado.",
                )
            }

            // La segregación de funciones vale para las DOS salidas (R-74). Si solo
            // aplicara a aprobar, rechazar sería la puerta de atrás para que alguien se
            // deshiciera de una discrepancia que él mismo creó.
            val own = pending.filter { it.second == rejectedBy }.map { it.first }
            if (own.isNotEmpty()) {
                throw UnprocessableException(
                    "SEGREGATION_OF_DUTIES",
                    "No puedes rechazar una discrepancia que tú mismo capturaste. " +
                        "Las líneas ${own.joinToString(", ")} las contó el usuario " +
                        "$rejectedBy, así que tiene que resolverlas otra persona.",
                    mapOf("lines" to own.joinToString(",")),
                )
            }

            if (reason.isBlank()) {
                throw UnprocessableException(
                    "REASON_REQUIRED",
                    "Rechazar una discrepancia exige un motivo: dentro de un año nadie " +
                        "podrá explicar por qué se canceló la línea.",
                )
            }

            connection.prepareStatement(
                """
                UPDATE invoice_lines
                SET status = 'CANCELLED'::invoice_line_status,
                    approved_by = ?, approved_at = now(),
                    adjustment_reason = ?::adjustment_reason,
                    updated_at = now()
                WHERE invoice_id = ?
                  AND status = 'DISCREPANCY'::invoice_line_status
                  AND NOT deleted
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, rejectedBy)
                st.setString(2, reason.uppercase())
                st.setLong(3, invoiceId)
                st.executeUpdate()
            }

            refreshInvoiceStatus(connection, invoiceId)

            audit.record(
                connection = connection,
                actorUserId = rejectedBy,
                channel = dev.axzy.stock.backend.audit.AuditWriter.Channel.WEB,
                entityType = "invoice",
                entityId = invoiceId.toString(),
                actorUsername = null,
                action = "invoice.discrepancy.reject",
                level = dev.axzy.stock.backend.audit.AuditWriter.Level.RELEVANT,
                previousState = """{"status":"DISCREPANCY","pendingLines":${pending.size}}""",
                newState = """{"status":"CANCELLED","cancelledLines":${pending.size},"adjustmentReason":"$reason"}""",
                reason = reason,
                note = note,
                permissionKey = "invoice.reject",
                decision = dev.axzy.stock.backend.audit.AuditWriter.Decision.ALLOWED,
                result = dev.axzy.stock.backend.audit.AuditWriter.Result.SUCCESS,
                businessDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC),
            )

            connection.commit()
            invoice(invoiceId)
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        }
    }

    /** Las líneas en discrepancia: id y quién las contó. */
    private fun pendingDiscrepancyLines(
        connection: java.sql.Connection,
        invoiceId: Long,
    ): List<Pair<Long, Long?>> = connection.prepareStatement(
        """
        SELECT id, captured_by FROM invoice_lines
        WHERE invoice_id = ? AND status = 'DISCREPANCY'::invoice_line_status AND NOT deleted
        """.trimIndent(),
    ).use { st ->
        st.setLong(1, invoiceId)
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.getLong("id") to rs.getLong("captured_by").takeIf { !rs.wasNull() }) }
        }
    }

    /** Bandeja de pendientes de aprobación. **Pantalla nueva: el legacy no la tenía.** */
    fun pendingApprovals(): List<PendingApprovalDto> = dataSource.connection.use { connection ->
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
            ).use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            rs.toPendingApprovalDto(),
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
     * Datos mínimos de una línea, para las comprobaciones internas.
     *
     * **No se convierte a DTO.** La primera versión lo hacía y devolvía un
     * placeholder: el cliente recibía `physicalQuantity: null` y `difference: null`
     * justo después de capturar, así que parecía que la captura no había guardado
     * nada. La respuesta se lee de [`leerLineas`], que es la única consulta que tiene
     * todos los campos.
     */
    private data class RawLine(
        val id: Long,
        val quantity: String,
        val status: String,
    )

    private fun readLineFor(connection: Connection, invoiceId: Long, lineId: Long): RawLine =
        connection.prepareStatement(
            """
            SELECT id, quantity, status FROM invoice_lines
            WHERE id = ? AND invoice_id = ? AND NOT deleted
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, lineId)
            st.setLong(2, invoiceId)
            st.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw NotFoundException(
                        "La línea $lineId no existe en la factura $invoiceId.",
                        "LINE_NOT_FOUND",
                    )
                }
                RawLine(
                    id = rs.getLong("id"),
                    quantity = rs.getBigDecimal("quantity").toPlainString(),
                    status = rs.getString("status"),
                )
            }
        }

    /**
     * Deriva el estado de la factura del estado de sus líneas.
     *
     * **No se mantiene a mano.** El legacy tenía el estado de la factura y el de sus
     * líneas como dos cosas que se actualizaban por separado, así que se
     * desincronizaban. Aquí se recalcula siempre desde las líneas, que son el dato
     * real.
     */
    private fun refreshInvoiceStatus(connection: Connection, invoiceId: Long) {
        val statuses = connection.prepareStatement(
            "SELECT status FROM invoice_lines WHERE invoice_id = ? AND NOT deleted",
        ).use { st ->
            st.setLong(1, invoiceId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("status")) }
            }
        }

        val new = when {
            statuses.isEmpty() -> "PENDING"
            statuses.any { it == "DISCREPANCY" } -> "PENDING_TO_LOCATE"
            statuses.all { it == "LOCATED" } -> "COMPLETED"
            statuses.all { it == "CANCELLED" } -> "CANCELLED"
            else -> "IN_PROGRESS"
        }

        connection.prepareStatement(
            "UPDATE invoices SET status = ?::invoice_status, updated_at = now() WHERE id = ?",
        ).use { st ->
            st.setString(1, new)
            st.setLong(2, invoiceId)
            st.executeUpdate()
        }
    }

}

/** Convierte a `BigDecimal` con 3 decimales, sin pasar por `Double`. */
private fun Quantity.toBigDecimal(): BigDecimal =
    BigDecimal.valueOf(this.value).movePointLeft(Quantity.SCALE)

// ── DTOs ────────────────────────────────────────────────────────────────────

/** Una línea nueva, tal como llega para crear la factura. */
data class NewInvoiceLine(
    val productCode: String,
    val container: String?,
    val unitMeasure: String,
    val quantity: Quantity,
    val boxQuantity: Quantity,
    val unitsPerBox: Quantity? = null,
)

@Serializable
data class InvoiceDetail(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val invoiceType: String,
    val status: String,
    val customerCode: String? = null,
    val concept: String? = null,
    val totalLines: Int = 0,
    val pendingApprovalLines: Int = 0,
    val lines: List<InvoiceLineDto> = emptyList(),
)

@Serializable
data class InvoiceLineDto(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val container: String? = null,
    val quantity: String,
    val boxQuantity: String,
    val unitsPerBox: String? = null,
    val physicalQuantity: String? = null,
    val physicalBoxQuantity: String? = null,
    val locatedQuantity: String,
    /** La calcula LA BASE, con su columna generada. */
    val difference: String? = null,
    val status: String,
    val capturedBy: Long? = null,
    val capturedByName: String? = null,
    val capturedAt: String? = null,
    val approvedBy: Long? = null,
    val approvedByName: String? = null,
    val approvedAt: String? = null,
    val adjustmentReason: String? = null,
    val locationDisplayName: String? = null,
)

@Serializable
data class PendingApprovalDto(
    val invoiceId: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val status: String,
    val pendingLines: Int,
    val totalDifference: String,
)
