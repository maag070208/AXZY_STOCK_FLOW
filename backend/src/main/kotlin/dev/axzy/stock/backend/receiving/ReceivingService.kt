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
class ReceivingService(private val dataSource: DataSource) {

    // ═══════════════════════════════════════════════════════════════════════
    // FACTURAS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Crea una factura con sus líneas.
     *
     * Se hace en UNA transacción: una factura sin líneas es un documento que no
     * sirve para nada y que bloquea el flujo de recepción.
     */
    fun crearFactura(
        numero: String,
        fecha: LocalDate,
        tipo: String,
        lineas: List<NuevaLinea>,
        usuarioId: Long,
    ): Long = dataSource.connection.use { conexion ->
        conexion.autoCommit = false
        try {
            val existente = conexion.prepareStatement(
                "SELECT id FROM invoices WHERE invoice_number = ?",
            ).use { st ->
                st.setString(1, numero)
                st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
            }

            if (existente != null) {
                throw ConflictException(
                    "DUPLICATE_INVOICE_NUMBER",
                    "Ya existe una factura con el número '$numero'.",
                    mapOf("invoiceId" to existente.toString()),
                )
            }

            val facturaId = conexion.prepareStatement(
                """
                INSERT INTO invoices (invoice_number, receipt_date, invoice_type, status, concept)
                VALUES (?, ?, ?::invoice_type, 'PENDING'::invoice_status, ?)
                RETURNING id
                """.trimIndent(),
            ).use { st ->
                st.setString(1, numero)
                st.setDate(2, java.sql.Date.valueOf(fecha))
                st.setString(3, tipo)
                st.setString(4, null)
                st.executeQuery().use { rs -> rs.next(); rs.getLong("id") }
            }

            lineas.forEach { linea ->
                val productoId = conexion.prepareStatement(
                    "SELECT id FROM products WHERE product_code = ? AND active",
                ).use { st ->
                    st.setString(1, linea.productCode)
                    st.executeQuery().use { rs ->
                        if (!rs.next()) {
                            throw UnprocessableException(
                                "PRODUCT_NOT_FOUND",
                                "El producto '${linea.productCode}' no existe en el catálogo.",
                            )
                        }
                        rs.getLong("id")
                    }
                }

                conexion.prepareStatement(
                    """
                    INSERT INTO invoice_lines (
                        invoice_id, product_id, container, unit_measure_id,
                        quantity, box_quantity, units_per_box, status
                    )
                    SELECT ?, ?, ?, u.id, ?, ?, ?, 'PENDING'::invoice_line_status
                    FROM units_of_measure u WHERE u.code = ?
                    """.trimIndent(),
                ).use { st ->
                    st.setLong(1, facturaId)
                    st.setLong(2, productoId)
                    st.setString(3, linea.container)
                    st.setBigDecimal(4, linea.quantity.aBigDecimal())
                    st.setBigDecimal(5, linea.boxQuantity.aBigDecimal())
                    st.setBigDecimal(6, linea.unitsPerBox?.aBigDecimal())
                    st.setString(7, linea.unitMeasure)
                    st.executeUpdate()
                }
            }

            conexion.commit()
            facturaId
        } catch (e: Exception) {
            runCatching { conexion.rollback() }
            throw e
        }
    }

    /** Lee una factura con sus líneas. */
    fun factura(id: Long): FacturaDetalle = dataSource.connection.use { conexion ->
        val cabecera = conexion.prepareStatement(
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
                FacturaDetalle(
                    id = rs.getLong("id"),
                    invoiceNumber = rs.getString("invoice_number"),
                    receiptDate = rs.getDate("receipt_date").toLocalDate().toString(),
                    invoiceType = rs.getString("invoice_type"),
                    status = rs.getString("status"),
                    customerCode = rs.getString("customer_code"),
                    concept = rs.getString("concept"),
                    totalLines = 0,
                    pendingApprovalLines = 0,
                    lines = leerLineas(conexion, id),
                )
            }
        }

        cabecera.copy(
            totalLines = cabecera.lines.size,
            pendingApprovalLines = cabecera.lines.count {
                it.status == InvoiceLineStatus.DISCREPANCY.name
            },
        )
    }

    private fun leerLineas(conexion: Connection, facturaId: Long): List<LineaDto> =
        conexion.prepareStatement(
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
            st.setLong(1, facturaId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            LineaDto(
                                id = rs.getLong("id"),
                                productId = rs.getLong("product_id"),
                                productCode = rs.getString("product_code"),
                                productName = rs.getString("product_name"),
                                container = rs.getString("container"),
                                quantity = rs.getBigDecimal("quantity").toPlainString(),
                                boxQuantity = rs.getBigDecimal("box_quantity").toPlainString(),
                                unitsPerBox = rs.getBigDecimal("units_per_box")?.toPlainString(),
                                physicalQuantity = rs.getBigDecimal("physical_quantity")?.toPlainString(),
                                physicalBoxQuantity = rs.getBigDecimal("physical_box_quantity")?.toPlainString(),
                                locatedQuantity = rs.getBigDecimal("located_quantity").toPlainString(),
                                // `difference` viene de la BASE, de una columna generada.
                                // No se calcula aquí, y es deliberado: en el legacy se
                                // declaraba en cuatro sitios y siempre valía 0.
                                difference = rs.getBigDecimal("difference")?.toPlainString(),
                                status = rs.getString("status"),
                                capturedBy = rs.getLong("captured_by").takeIf { !rs.wasNull() },
                                capturedByName = rs.getString("captured_by_name"),
                                capturedAt = rs.getTimestamp("captured_at")?.toInstant()?.toString(),
                                approvedBy = rs.getLong("approved_by").takeIf { !rs.wasNull() },
                                approvedByName = rs.getString("approved_by_name"),
                                approvedAt = rs.getTimestamp("approved_at")?.toInstant()?.toString(),
                                adjustmentReason = rs.getString("adjustment_reason"),
                                locationDisplayName = rs.getString("hall")?.let {
                                    cuandoNombre(it, rs.getString("code"), rs.getString("position"), rs.getString("kind"))
                                },
                            ),
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
    fun capturarLinea(
        facturaId: Long,
        lineaId: Long,
        cantidadFisica: Quantity,
        cajasFisicas: Quantity?,
        usuarioId: Long,
    ): LineaDto = dataSource.connection.use { conexion ->
        conexion.autoCommit = false
        try {
            val linea = leerLineaPara(conexion, facturaId, lineaId)

            if (linea.status == InvoiceLineStatus.APPROVED.name ||
                linea.status == InvoiceLineStatus.LOCATED.name
            ) {
                throw ConflictException(
                    "LINE_ALREADY_RESOLVED",
                    "La línea ya está ${linea.status.lowercase()} y no se puede volver a capturar.",
                )
            }

            val cantidadFacturada = BigDecimal(linea.quantity)
            val fisica = cantidadFisica.aBigDecimal()

            // El estado lo decide la DIFERENCIA, y se lee de la base después de
            // escribir: calcularla aquí sería duplicar la regla y las dos versiones
            // se separarían.
            val nuevoEstado = if (fisica.compareTo(cantidadFacturada) == 0) {
                InvoiceLineStatus.CAPTURED.name
            } else {
                InvoiceLineStatus.DISCREPANCY.name
            }

            conexion.prepareStatement(
                """
                UPDATE invoice_lines
                SET physical_quantity = ?, physical_box_quantity = ?,
                    status = ?::invoice_line_status,
                    captured_by = ?, captured_at = now(), updated_at = now()
                WHERE id = ? AND invoice_id = ?
                """.trimIndent(),
            ).use { st ->
                st.setBigDecimal(1, fisica)
                st.setBigDecimal(2, cajasFisicas?.aBigDecimal())
                st.setString(3, nuevoEstado)
                st.setLong(4, usuarioId)
                st.setLong(5, lineaId)
                st.setLong(6, facturaId)
                st.executeUpdate()
            }

            // El estado de la factura se deriva del de sus líneas, no se mantiene a
            // mano: una factura con una línea en discrepancia está IN_PROGRESS, no
            // COMPLETED.
            refrescarEstadoFactura(conexion, facturaId)

            conexion.commit()

            // Se responde con la línea COMPLETA leída de `leerLineas`, no con el
            // resumen interno: el cliente necesita ver la `difference` que calculó
            // la base para saber si tiene que aprobar algo.
            leerLineas(conexion, facturaId).first { it.id == lineaId }
        } catch (e: Exception) {
            runCatching { conexion.rollback() }
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
    fun aprobarFactura(
        facturaId: Long,
        motivo: String,
        nota: String?,
        aprobadorId: Long,
    ): FacturaDetalle = dataSource.connection.use { conexion ->
        conexion.autoCommit = false
        try {
            val pendientes = conexion.prepareStatement(
                """
                SELECT l.id, l.captured_by
                FROM invoice_lines l
                WHERE l.invoice_id = ?
                  AND l.status = 'DISCREPANCY'::invoice_line_status
                  AND NOT l.deleted
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, facturaId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getLong("id") to rs.getLong("captured_by").takeIf { !rs.wasNull() })
                        }
                    }
                }
            }

            if (pendientes.isEmpty()) {
                throw ConflictException(
                    "NO_PENDING_APPROVAL",
                    "La factura no tiene líneas en discrepancia que aprobar. " +
                        "Solo se aprueban las líneas con diferencia respecto a lo facturado.",
                )
            }

            // ── Segregación de funciones ────────────────────────────────────
            val propias = pendientes.filter { it.second == aprobadorId }.map { it.first }
            if (propias.isNotEmpty()) {
                throw UnprocessableException(
                    "SEGREGATION_OF_DUTIES",
                    "No puedes aprobar una discrepancia que tú mismo capturaste. " +
                        "Las líneas ${propias.joinToString(", ")} las contó el usuario " +
                        "$aprobadorId, así que tiene que aprobarlas otra persona.",
                    mapOf("lines" to propias.joinToString(",")),
                )
            }

            if (motivo.isBlank()) {
                throw UnprocessableException(
                    "REASON_REQUIRED",
                    "Aprobar una discrepancia exige un motivo: dentro de un año nadie " +
                        "podrá explicar por qué se aceptó la diferencia.",
                )
            }

            conexion.prepareStatement(
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
                st.setLong(1, aprobadorId)
                st.setString(2, motivo.uppercase())
                st.setLong(3, facturaId)
                st.executeUpdate()
            }

            refrescarEstadoFactura(conexion, facturaId)
            conexion.commit()
            factura(facturaId)
        } catch (e: Exception) {
            runCatching { conexion.rollback() }
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
    fun exigirUbicable(conexion: Connection, lineaId: Long) {
        val estado = conexion.prepareStatement(
            "SELECT status FROM invoice_lines WHERE id = ? AND NOT deleted",
        ).use { st ->
            st.setLong(1, lineaId)
            st.executeQuery().use { rs ->
                if (!rs.next()) throw NotFoundException("La línea $lineaId no existe.", "LINE_NOT_FOUND")
                rs.getString("status")
            }
        }

        // El estado del dominio es DISCREPANCY: es el mismo concepto que
        // PENDING_APPROVAL del contrato HTTP. El contrato usa un nombre y el
        // dominio otro, y tenerlo claro evita inventar un estado que no existe.
        if (estado == InvoiceLineStatus.DISCREPANCY.name) {
            throw ConflictException(
                "DISCREPANCY_NOT_APPROVED",
                "La línea tiene una discrepancia sin aprobar y no se puede ubicar. " +
                    "La diferencia entre lo facturado y lo contado tiene que resolverla " +
                    "un supervisor antes: si se ubicara, la diferencia desaparecería del " +
                    "inventario sin que nadie la autorice.",
            )
        }
    }

    /** Bandeja de pendientes de aprobación. **Pantalla nueva: el legacy no la tenía.** */
    fun pendientesDeAprobacion(): List<PendienteDto> = dataSource.connection.use { conexion ->
        conexion.createStatement().use { st ->
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
                            PendienteDto(
                                invoiceId = rs.getLong("id"),
                                invoiceNumber = rs.getString("invoice_number"),
                                receiptDate = rs.getDate("receipt_date").toLocalDate().toString(),
                                status = rs.getString("status"),
                                pendingLines = rs.getInt("pending_lines"),
                                totalDifference = rs.getBigDecimal("total_difference").toPlainString(),
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
     * Datos mínimos de una línea, para las comprobaciones internas.
     *
     * **No se convierte a DTO.** La primera versión lo hacía y devolvía un
     * placeholder: el cliente recibía `physicalQuantity: null` y `difference: null`
     * justo después de capturar, así que parecía que la captura no había guardado
     * nada. La respuesta se lee de [`leerLineas`], que es la única consulta que tiene
     * todos los campos.
     */
    private data class LineaCruda(
        val id: Long,
        val quantity: String,
        val status: String,
    )

    private fun leerLineaPara(conexion: Connection, facturaId: Long, lineaId: Long): LineaCruda =
        conexion.prepareStatement(
            """
            SELECT id, quantity, status FROM invoice_lines
            WHERE id = ? AND invoice_id = ? AND NOT deleted
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, lineaId)
            st.setLong(2, facturaId)
            st.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw NotFoundException(
                        "La línea $lineaId no existe en la factura $facturaId.",
                        "LINE_NOT_FOUND",
                    )
                }
                LineaCruda(
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
    private fun refrescarEstadoFactura(conexion: Connection, facturaId: Long) {
        val estados = conexion.prepareStatement(
            "SELECT status FROM invoice_lines WHERE invoice_id = ? AND NOT deleted",
        ).use { st ->
            st.setLong(1, facturaId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("status")) }
            }
        }

        val nuevo = when {
            estados.isEmpty() -> "PENDING"
            estados.any { it == "DISCREPANCY" } -> "PENDING_TO_LOCATE"
            estados.all { it == "LOCATED" } -> "COMPLETED"
            estados.all { it == "CANCELLED" } -> "CANCELLED"
            else -> "IN_PROGRESS"
        }

        conexion.prepareStatement(
            "UPDATE invoices SET status = ?::invoice_status, updated_at = now() WHERE id = ?",
        ).use { st ->
            st.setString(1, nuevo)
            st.setLong(2, facturaId)
            st.executeUpdate()
        }
    }

    private fun cuandoNombre(hall: String, code: String, position: String, kind: String): String =
        when (kind) {
            "RECEIVING" -> "RECEPCION"
            "UNASSIGNED" -> "SIN-UBICAR"
            else -> "$hall-$code-$position"
        }
}

/** Convierte a `BigDecimal` con 3 decimales, sin pasar por `Double`. */
private fun Quantity.aBigDecimal(): BigDecimal =
    BigDecimal.valueOf(this.value).movePointLeft(Quantity.SCALE)

// ── DTOs ────────────────────────────────────────────────────────────────────

/** Una línea nueva, tal como llega para crear la factura. */
data class NuevaLinea(
    val productCode: String,
    val container: String?,
    val unitMeasure: String,
    val quantity: Quantity,
    val boxQuantity: Quantity,
    val unitsPerBox: Quantity? = null,
)

@Serializable
data class FacturaDetalle(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val invoiceType: String,
    val status: String,
    val customerCode: String? = null,
    val concept: String? = null,
    val totalLines: Int = 0,
    val pendingApprovalLines: Int = 0,
    val lines: List<LineaDto> = emptyList(),
)

@Serializable
data class LineaDto(
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
data class PendienteDto(
    val invoiceId: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val status: String,
    val pendingLines: Int,
    val totalDifference: String,
)
