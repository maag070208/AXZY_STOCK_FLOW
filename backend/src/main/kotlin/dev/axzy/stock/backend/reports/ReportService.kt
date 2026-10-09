package dev.axzy.stock.backend.reports

import dev.axzy.stock.backend.error.BadRequestException
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDate
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REPORTES (§9) — consultas de solo lectura
 *
 * **Nada de este archivo escribe.** Los tres reportes leen proyecciones y el
 * ledger, y ninguno abre transacción de escritura. Es deliberado: un reporte que
 * escribe deja de ser un reporte y pasa a ser un efecto secundario que nadie
 * audita.
 *
 * ── POR QUÉ SQL DIRECTO Y NO EXPOSED ───────────────────────────────────────
 *
 * Estas consultas son `GROUP BY` con `SUM` y `COUNT(DISTINCT)`. Es justo lo que
 * el motor hace mejor, y el DSL de Exposed no aporta seguridad de tipos aquí:
 * las columnas de una agregación no son columnas de una tabla, así que se
 * acabaría escribiendo `SUM(...)` en cadena igual. Se sigue el mismo criterio que
 * `InventoryService` (§1.5).
 *
 * ── LAS CANTIDADES SE LEEN COMO `BigDecimal` Y SE MANDAN COMO TEXTO ────────
 *
 * `getBigDecimal(...).toPlainString()` da siempre 3 decimales (`"5193.000"`)
 * porque las columnas son `NUMERIC(14,3)`. Pasar por `Double` es lo que producía
 * los descuadres de redondeo del legacy.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ReportService(private val dataSource: DataSource) {

    /**
     * La zona con la que se interpretan las fechas OPERATIVAS del reporte.
     *
     * **No se usa UTC, y costó un diagnóstico.** El servidor de base de datos
     * corre en `America/Tijuana` (UTC−7) y `moved_at` es `timestamptz`. Filtrar
     * por `00:00 UTC` deja fuera los movimientos de la tarde: un movimiento de
     * las 23:11 del día 7 es, en UTC, las 06:11 del día 8 — **el reporte del
     * día 7 no lo veía**. El síntoma era un informe vacío con datos en la base.
     *
     * La fecha operativa es la del ALMACÉN, no la de Greenwich: un turno de
     * noche que captura a las 23:00 pertenece a ese día. Se usa la zona del
     * servidor, que es la que PostgreSQL aplica en `CURRENT_DATE` y en
     * `date_trunc`, así que el filtro y la agrupación coinciden.
     */
    private val zona: java.time.ZoneId = java.time.ZoneId.systemDefault()

    // ── Tipos de movimiento, agrupados por su EFECTO en el inventario ───────
    //
    // Se listan explícitamente en vez de usar «todo lo que no sea OUT es IN»: un
    // tipo nuevo en el enum (`PENDING_OUT`, por ejemplo) entraría solo en la
    // primera suma y **contaría como entrada una intención de salida**, que no
    // mueve inventario. La lista explícita obliga a decidir dónde va cada uno.

    private val inTypes = listOf("INITIAL", "RECEIPT_IN", "ADJUSTMENT_IN", "TRANSFER_IN")

    private val outTypes = listOf("FULFILLMENT_OUT", "ADJUSTMENT_OUT", "TRANSFER_OUT")

    // ── Los que NO cambian el total, y por eso NO suman en ninguna columna ──
    //
    // **`LOCATE` y `MOVE_WITHIN_WAREHOUSE` mueven mercancía ENTRE UBICACIONES.**
    // `registrar_movimiento` llama a `recalcular_total_producto`, que suma
    // `stock_by_location` + `stock_in_transit`: mover de un estante a otro resta
    // en uno y suma en el otro, así que **el total no se mueve**.
    //
    // Contarlos como salida era el error fácil —el nombre `MOVE_..._OUT` no
    // aparece, pero `OUT` sí está en `FULFILLMENT_OUT`— y habría inflado la
    // columna de salidas con traslados internos. El usuario habría visto «salieron
    // 8.300 unidades» cuando solo se movieron de sitio, y **la cifra no cuadraría
    // con el kardex**: el síntoma perfecto para desconfiar del reporte entero.
    //
    // `PENDING_OUT` tampoco suma: es una INTENCIÓN de salida y el enum lo dice
    // («NO cambia el inventario»). No está aquí abajo porque se excluye por no
    // estar en ninguna de las tres listas; se documenta para que nadie lo añada.

    // ═════════════════════════════════════════════════════════════════════════
    // 1. EXISTENCIAS POR PRODUCTO Y UBICACIÓN
    // ═════════════════════════════════════════════════════════════════════════

    fun inventory(
        warehouseCode: String?,
        productCode: String?,
        onlyWithStock: Boolean,
        offset: Int,
        limit: Int,
    ): List<InventoryReportItem> = dataSource.connection.use { connection ->
        val (filtro, parametros) = inventoryFilter(warehouseCode, productCode, onlyWithStock)

        val sql = """
            SELECT p.product_code, p.name,
                   w.code AS warehouse_code,
                   CASE l.kind
                       WHEN 'RECEIVING'  THEN 'RECEPCION'
                       WHEN 'UNASSIGNED' THEN 'SIN-UBICAR'
                       ELSE l.hall || '-' || l.code || '-' || l."position"
                   END AS location_display_name,
                   s.quantity
            FROM stock_by_location s
            JOIN products p  ON p.id = s.product_id
            JOIN locations l ON l.id = s.location_id
            JOIN warehouses w ON w.id = l.warehouse_id
            $filtro
            ORDER BY p.product_code, location_display_name
            OFFSET ? LIMIT ?
        """.trimIndent()

        connection.prepareStatement(sql).use { st ->
            var i = 1
            parametros.forEach { st.setObject(i++, it) }
            st.setInt(i++, offset)
            st.setInt(i, limit)

            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            InventoryReportItem(
                                productCode = rs.getString("product_code"),
                                name = rs.getString("name"),
                                locationDisplayName = rs.getString("location_display_name"),
                                quantity = rs.getBigDecimal("quantity").toPlainString(),
                                warehouseCode = rs.getString("warehouse_code"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Cuántas filas cumplen el filtro. Alimenta el `totalItems` del sobre. */
    fun countInventory(
        warehouseCode: String?,
        productCode: String?,
        onlyWithStock: Boolean,
    ): Long = dataSource.connection.use { connection ->
        val (filtro, parametros) = inventoryFilter(warehouseCode, productCode, onlyWithStock)
        val sql = """
            SELECT count(*)
            FROM stock_by_location s
            JOIN products p  ON p.id = s.product_id
            JOIN locations l ON l.id = s.location_id
            JOIN warehouses w ON w.id = l.warehouse_id
            $filtro
        """.trimIndent()

        connection.prepareStatement(sql).use { st ->
            var i = 1
            parametros.forEach { st.setObject(i++, it) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    /**
     * Totales del reporte.
     *
     * **Se calculan sobre el filtro ENTERO, no sobre la página.** Es la misma
     * lección que el `totalItems` de §1.4: si el total se calculara sobre las 50
     * filas devueltas, el reporte diría «1.398 productos, 10.914.130 unidades»
     * cuando el usuario está mirando una bodega con 12. Un total que ignora el
     * filtro es peor que no tenerlo, porque el usuario decide con él.
     */
    fun inventoryTotals(
        warehouseCode: String?,
        productCode: String?,
        onlyWithStock: Boolean,
    ): InventoryReportTotals = dataSource.connection.use { connection ->
        val (filtro, parametros) = inventoryFilter(warehouseCode, productCode, onlyWithStock)
        val sql = """
            SELECT count(DISTINCT s.product_id) AS distinct_products,
                   COALESCE(SUM(s.quantity), 0) AS total_units
            FROM stock_by_location s
            JOIN products p  ON p.id = s.product_id
            JOIN locations l ON l.id = s.location_id
            JOIN warehouses w ON w.id = l.warehouse_id
            $filtro
        """.trimIndent()

        connection.prepareStatement(sql).use { st ->
            var i = 1
            parametros.forEach { st.setObject(i++, it) }
            st.executeQuery().use { rs ->
                if (!rs.next()) return@use InventoryReportTotals(0, "0.000")
                InventoryReportTotals(
                    distinctProducts = rs.getLong("distinct_products"),
                    // `setScale(3)`: `SUM` sobre cero filas devuelve un NUMERIC
                    // con la escala que le apetezca —`0` a secas— y el contrato
                    // fija 3 decimales. Un «0» suelto rompe el formato de la
                    // columna para todo cliente que la parsee.
                    totalUnits = rs.getBigDecimal("total_units").setScale(3).toPlainString(),
                )
            }
        }
    }

    /** El `WHERE` compartido por las tres consultas de existencias. */
    private fun inventoryFilter(
        warehouseCode: String?,
        productCode: String?,
        onlyWithStock: Boolean,
    ): Pair<String, List<Any>> {
        val condiciones = mutableListOf<String>()
        val parametros = mutableListOf<Any>()

        if (!warehouseCode.isNullOrBlank()) {
            condiciones += "AND w.code = ?"
            parametros += warehouseCode.trim()
        }
        if (!productCode.isNullOrBlank()) {
            condiciones += "AND p.product_code = ?"
            parametros += productCode.trim()
        }
        if (onlyWithStock) {
            // El nombre dice «solo con existencia»: sin esta condición el reporte
            // listaría miles de filas con 0.000 que nadie quiere ver.
            condiciones += "AND s.quantity > 0"
        }

        val where = if (condiciones.isEmpty()) "" else "WHERE " + condiciones.joinToString(" ").removePrefix("AND ")
        return where to parametros
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. RESUMEN DE MOVIMIENTOS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Resumen de movimientos del kardex agrupado por día, semana, mes, producto o
     * ubicación.
     *
     * @param groupBy ya validado contra la lista del contrato.
     */
    fun movementsSummary(
        from: LocalDate,
        to: LocalDate,
        groupBy: String,
    ): Pair<MovementsSummaryTotals, List<MovementsSummaryItem>> {
        // Se valida ANTES de tocar la base: un parámetro inválido no debe abrir
        // una conexión para acabar lanzando. Además así el `400` no depende de
        // que la base responda.
        val clave = groupingExpression(groupBy)

        return dataSource.connection.use { connection ->

            // `IN` con la lista de tipos construida con marcadores: **nunca
            // concatenando valores**. Se genera un `?` por tipo y se pasan como
            // parámetros, así que la lista de tipos viene del código y no de la
            // petición; un `IN` con cadenas interpoladas sería la puerta clásica.
            val inMarcas = inTypes.joinToString(",") { "?" }
            val outMarcas = outTypes.joinToString(",") { "?" }

            val sql = """
                SELECT $clave AS group_key,
                       SUM(CASE WHEN k.move_type::text IN ($inMarcas)  THEN k.units ELSE 0 END) AS in_units,
                       SUM(CASE WHEN k.move_type::text IN ($outMarcas) THEN k.units ELSE 0 END) AS out_units,
                       count(*) AS movements
                FROM kardex k
                JOIN products p  ON p.id = k.product_id
                LEFT JOIN locations l ON l.id = k.location_id
                LEFT JOIN warehouses w ON w.id = l.warehouse_id
                WHERE k.moved_at >= ? AND k.moved_at < ?
                GROUP BY group_key
                ORDER BY group_key
            """.trimIndent()

            connection.prepareStatement(sql).use { st ->
                var i = 1
                inTypes.forEach { st.setString(i++, it) }
                outTypes.forEach { st.setString(i++, it) }
                // El rango es [desde, hasta): la medianoche del día siguiente
                // como límite EXCLUSIVO. Con `<=` a las 23:59:59 se perderían los
                // movimientos del último segundo del día, que es un fallo que solo
                // aparece el día que alguien cierra un turno a esa hora.
                st.setObject(i++, java.time.OffsetDateTime.ofInstant(from.atStartOfDay(zona).toInstant(), zona))
                st.setObject(i, java.time.OffsetDateTime.ofInstant(to.plusDays(1).atStartOfDay(zona).toInstant(), zona))

                val items = mutableListOf<MovementsSummaryItem>()
                var totalIn = BigDecimal.ZERO.setScale(3)
                var totalOut = BigDecimal.ZERO.setScale(3)
                var totalMovements = 0L

                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        val entrada = rs.getBigDecimal("in_units") ?: BigDecimal.ZERO.setScale(3)
                        val salida = rs.getBigDecimal("out_units") ?: BigDecimal.ZERO.setScale(3)
                        val movimientos = rs.getLong("movements")
                        val claveValor = rs.getString("group_key") ?: "—"

                        totalIn = totalIn.add(entrada)
                        totalOut = totalOut.add(salida)
                        totalMovements += movimientos

                        items += MovementsSummaryItem(
                            key = claveValor,
                            // La etiqueta es la MISMA que la clave salvo en la
                            // fecha, donde el contrato pide la forma de mostrar.
                            // Se deja igual a propósito: formatear según el idioma
                            // es del cliente, y el servidor no conoce su locale.
                            keyLabel = claveValor,
                            inUnits = entrada.toPlainString(),
                            outUnits = salida.toPlainString(),
                            netUnits = entrada.subtract(salida).toPlainString(),
                            movements = movimientos,
                        )
                    }
                }

                MovementsSummaryTotals(
                    inUnits = totalIn.setScale(3).toPlainString(),
                    outUnits = totalOut.setScale(3).toPlainString(),
                    netUnits = totalIn.subtract(totalOut).setScale(3).toPlainString(),
                    movements = totalMovements,
                ) to items
            }
        }
    }

    /**
     * La expresión de agrupación de cada `groupBy` del contrato.
     *
     * **`ELSE` no existe a propósito.** Un `groupBy` que no esté en esta lista ya
     * se rechazó antes con `400 INVALID_PARAMETER`; si aquí hubiera un `ELSE`,
     * un valor nuevo acabaría agrupando por algo arbitrario en vez de fallar.
     */
    private fun groupingExpression(groupBy: String): String = when (groupBy) {
        // `date_trunc` sobre `timestamptz` usa la zona de la SESIÓN, que es la
        // misma que `zona`. La agrupación y el filtro así coinciden: si una usara
        // UTC y la otra la zona del almacén, un movimiento de la tarde aparecería
        // en un día distinto al que se filtró.
        "day" -> "to_char(date_trunc('day', k.moved_at), 'YYYY-MM-DD')"
        "week" -> "to_char(date_trunc('week', k.moved_at), 'YYYY-MM-DD')"
        "month" -> "to_char(date_trunc('month', k.moved_at), 'YYYY-MM')"
        "product" -> "p.product_code"
        // Misma composición que el resto del sistema, y el `ELSE` cubre las tres
        // variantes reales del enum (`SHELF`, `RECEIVING`, `UNASSIGNED`).
        "location" -> """
            CASE l.kind
                WHEN 'RECEIVING'  THEN 'RECEPCION'
                WHEN 'UNASSIGNED' THEN 'SIN-UBICAR'
                ELSE l.hall || '-' || l.code || '-' || l."position"
            END
        """.trimIndent()
        else -> throw BadRequestException(
            code = "INVALID_PARAMETER",
            message = "El parámetro 'groupBy' no admite el valor '$groupBy'.",
            details = mapOf(
                "groupBy" to groupBy,
                "allowed" to GROUP_BY_ALLOWED.joinToString(", "),
            ),
        )
    }


    // ═════════════════════════════════════════════════════════════════════════
    // 3. EXPORTACIÓN DEL KARDEX
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * El kardex filtrado, como tabla plana lista para CSV o XLSX.
     *
     * **Las columnas son las mismas que las del listado de la pantalla**, y no es
     * casualidad: §9 exige que «si el usuario ve 200 filas filtradas y exporta,
     * reciba esas 200». Un archivo con otras columnas obliga al usuario a
     * reconciliar dos vistas de los mismos datos, que es exactamente lo que el
     * reporte viene a evitar.
     *
     * Los filtros de fecha se aplican con `[desde, hasta]` inclusive en el día
     * `hasta`, que es lo que espera quien escribe «del 1 al 31»: **quedarse con el
     * 31 fuera del rango por un `<=` mal puesto es un fallo que solo se nota el
     * día que alguien cuadra el mes**.
     */
    fun kardexExport(
        productCode: String?,
        type: String?,
        from: LocalDate?,
        until: LocalDate?,
    ): ExportTable = dataSource.connection.use { connection ->
        val sql = buildString {
            append(
                """
                SELECT k.moved_at, p.product_code, p.name AS product_name, k.move_type,
                       k.units, k.quantity_after,
                       CASE lo.kind
                           WHEN 'RECEIVING'  THEN 'RECEPCION'
                           WHEN 'UNASSIGNED' THEN 'SIN-UBICAR'
                           ELSE lo.hall || '-' || lo.code || '-' || lo."position"
                       END AS from_display,
                       CASE ld.kind
                           WHEN 'RECEIVING'  THEN 'RECEPCION'
                           WHEN 'UNASSIGNED' THEN 'SIN-UBICAR'
                           ELSE ld.hall || '-' || ld.code || '-' || ld."position"
                       END AS to_display,
                       k.container, k.order_number, k.adjustment_reason,
                       k.adjustment_origin, u.name AS user_name, k.note
                FROM kardex k
                JOIN products p ON p.id = k.product_id
                LEFT JOIN locations lo ON lo.id = k.from_location_id
                LEFT JOIN locations ld ON ld.id = COALESCE(k.to_location_id, k.location_id)
                LEFT JOIN users u ON u.id = k.user_id
                WHERE 1 = 1
                """.trimIndent(),
            )
            if (productCode != null) append(" AND p.product_code = ?")
            if (type != null) append(" AND k.move_type = ?::movement_type")
            if (from != null) append(" AND k.moved_at >= ?")
            if (until != null) append(" AND k.moved_at < ?")
            append(" ORDER BY k.moved_at DESC, k.id DESC")
        }

        connection.prepareStatement(sql).use { st ->
            var i = 1
            if (productCode != null) st.setString(i++, productCode)
            if (type != null) st.setString(i++, type)
            if (from != null) {
                st.setObject(i++, java.time.OffsetDateTime.ofInstant(from.atStartOfDay(zona).toInstant(), zona))
            }
            if (until != null) {
                // La medianoche del día SIGUIENTE, como límite exclusivo: así el
                // día `hasta` entra entero sin recurrir a `23:59:59`, que se deja
                // fuera el último segundo.
                st.setObject(
                    i,
                    java.time.OffsetDateTime.ofInstant(until.plusDays(1).atStartOfDay(zona).toInstant(), zona),
                )
            }

            val filas = mutableListOf<List<String>>()
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    filas += listOf(
                        rs.getTimestamp("moved_at").toInstant().toString(),
                        rs.getString("product_code"),
                        rs.getString("product_name"),
                        rs.getString("move_type"),
                        rs.getBigDecimal("units").toPlainString(),
                        rs.getBigDecimal("quantity_after").toPlainString(),
                        rs.getString("from_display") ?: "",
                        rs.getString("to_display") ?: "",
                        rs.getString("container") ?: "",
                        rs.getString("order_number") ?: "",
                        rs.getString("adjustment_reason") ?: "",
                        rs.getString("adjustment_origin") ?: "",
                        rs.getString("user_name") ?: "",
                        rs.getString("note") ?: "",
                    )
                }
            }

            ExportTable(
                headers = listOf(
                    "Fecha", "Producto", "Nombre", "Movimiento", "Unidades",
                    "Saldo después", "Desde", "Hacia", "Contenedor", "Orden",
                    "Motivo", "Origen", "Usuario", "Nota",
                ),
                rows = filas,
            )
        }
    }

    companion object {
        /** Los valores admitidos de `groupBy`, tal como los documenta §9. */
        val GROUP_BY_ALLOWED = listOf("day", "week", "month", "product", "location")

        /** Los formatos de exportación admitidos. */
        val EXPORT_FORMATS = listOf("csv", "xlsx")
    }
}

/**
 * Convierte una cadena de fecha `AAAA-MM-DD` del contrato en una fecha.
 *
 * Lanza `400 INVALID_FILTER_DATE` —y no un `DateTimeParseException` que acabaría
 * en un 500— porque una fecha mal escrita es culpa del cliente y el contrato
 * tiene un código para decirlo.
 */
internal fun parseOperationalDate(raw: String?, parametro: String): LocalDate {
    if (raw.isNullOrBlank()) {
        throw BadRequestException(
            code = "FIELD_REQUIRED",
            message = "El parámetro '$parametro' es obligatorio (formato AAAA-MM-DD).",
            details = mapOf(parametro to "obligatorio"),
        )
    }
    return runCatching { LocalDate.parse(raw.trim()) }.getOrElse {
        throw BadRequestException(
            code = "INVALID_FILTER_DATE",
            message = "El parámetro '$parametro' debe ser una fecha AAAA-MM-DD. Se recibió '$raw'.",
            details = mapOf(parametro to raw),
        )
    }
}

