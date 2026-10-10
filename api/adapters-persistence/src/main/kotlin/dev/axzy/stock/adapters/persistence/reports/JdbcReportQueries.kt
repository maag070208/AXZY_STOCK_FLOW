package dev.axzy.stock.adapters.persistence.reports

import dev.axzy.stock.adapters.persistence.inventory.JdbcInventoryQueries
import dev.axzy.stock.adapters.persistence.shared.atStartOfDayIn
import dev.axzy.stock.adapters.persistence.shared.toQuantity
import dev.axzy.stock.application.inventory.KardexFilter
import dev.axzy.stock.application.inventory.KardexLine
import dev.axzy.stock.application.inventory.StockLine
import dev.axzy.stock.application.reports.Divergence
import dev.axzy.stock.application.reports.InventoryReportFilter
import dev.axzy.stock.application.reports.InventoryTotals
import dev.axzy.stock.application.reports.MovementSummaryFilter
import dev.axzy.stock.application.reports.ReportQueries
import dev.axzy.stock.application.reports.SummaryGroup
import dev.axzy.stock.application.reports.SummaryLine
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.value.Quantity
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.ZoneId

/**
 * Las consultas de los reportes (docs/api/reportes.md).
 *
 * Escriben su propio SQL en lugar de reutilizar el de inventario porque el reporte
 * de existencias filtra por **bodega** y devuelve totales sobre el filtro entero,
 * que son cosas que el listado de inventario no hace. Lo que sí se reutiliza es el
 * troceo de la exportación, que es exactamente la misma consulta paginada.
 */
class JdbcReportQueries(
    private val jdbc: JdbcClient,
    /** El kardex paginado del inventario: la exportación lo lee por trozos. */
    private val kardexPages: JdbcInventoryQueries,
    /** La zona del negocio: el día de un resumen es el día del almacén. */
    private val businessZone: ZoneId,
) : ReportQueries {

    override fun inventory(filter: InventoryReportFilter, request: PageRequest): Page<StockLine> {
        val where = inventoryWhere(filter)
        val clause = clauseOf(where.first)

        val total = query("SELECT count(*) $INVENTORY_FROM $clause", where.second) { rs, _ -> rs.getLong(1) }.single()
        val items = query(
            "$INVENTORY_COLUMNS $INVENTORY_FROM $clause " +
                "ORDER BY p.product_code, l.hall, l.code, l.position, s.location_id LIMIT :limit OFFSET :offset",
            where.second + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toStockLine() }

        return Page(items, request.page, request.size, total)
    }

    override fun inventoryTotals(filter: InventoryReportFilter): InventoryTotals {
        val where = inventoryWhere(filter)
        return query(
            """
            SELECT count(DISTINCT s.product_id) AS products, COALESCE(sum(s.quantity), 0) AS units
            $INVENTORY_FROM ${clauseOf(where.first)}
            """.trimIndent(),
            where.second,
        ) { rs, _ ->
            InventoryTotals(distinctProducts = rs.getLong("products"), totalUnits = rs.getBigDecimal("units").toQuantity())
        }.single()
    }

    override fun movementSummary(filter: MovementSummaryFilter): List<SummaryLine> =
        when (filter.groupBy) {
            SummaryGroup.LOCATION -> summaryByLocation(filter)
            else -> summaryByGroupExpression(filter)
        }

    /**
     * Agrupa por una expresión de la propia fila: el día, la semana, el mes o el
     * producto.
     *
     * `LOCATE` y `MOVE_WITHIN_WAREHOUSE` **no suman en ninguna columna** y `PENDING_OUT`
     * tampoco: los dos primeros mueven mercancía entre ubicaciones —el total no
     * cambia— y el tercero es una intención de salida. Contarlos como salida inflaría
     * la cifra y no cuadraría con el kardex.
     */
    private fun summaryByGroupExpression(filter: MovementSummaryFilter): List<SummaryLine> {
        val expression = when (filter.groupBy) {
            SummaryGroup.PRODUCT -> "p.product_code"
            // `AT TIME ZONE` convierte el instante a la hora del almacén ANTES de
            // truncar: sin eso, un movimiento de las 21:00 del día 9 se agruparía en
            // el día 10 y el resumen no cuadraría con lo que pasó en el piso.
            else -> "to_char(${filter.groupBy.sql}, 'YYYY-MM-DD')"
        }
        return query(
            """
            SELECT $expression AS key_text,
                   COALESCE(sum(CASE WHEN k.move_type IN ($IN_TYPES) THEN k.units ELSE 0 END), 0) AS in_units,
                   COALESCE(sum(CASE WHEN k.move_type IN ($OUT_TYPES) THEN k.units ELSE 0 END), 0) AS out_units,
                   count(*) AS movements
            FROM kardex k JOIN products p ON p.id = k.product_id
            WHERE k.moved_at >= :from AND k.moved_at < :to
            GROUP BY 1 ORDER BY 1
            """.trimIndent(),
            rangeParams(filter),
        ) { rs, _ ->
            val key = rs.getString("key_text")
            SummaryLine(
                key = key,
                keyLabel = key,
                inUnits = rs.getBigDecimal("in_units").toQuantity(),
                outUnits = rs.getBigDecimal("out_units").toQuantity(),
                movements = rs.getInt("movements"),
            )
        }
    }

    /**
     * Agrupa por ubicación.
     *
     * Un movimiento tiene origen y destino, así que se cuenta **dos veces**: lo que
     * entra en el destino y lo que sale del origen. Agrupar por una sola de las dos
     * columnas dejaría a la otra ubicación con ceros y el resumen no cuadraría con lo
     * que pasó en el estante.
     */
    private fun summaryByLocation(filter: MovementSummaryFilter): List<SummaryLine> {
        val params = rangeParams(filter)
        return query(
            """
            SELECT l.hall, l.code, l.position, l.kind::text AS kind,
                   COALESCE(sum(x.in_units), 0) AS in_units,
                   COALESCE(sum(x.out_units), 0) AS out_units,
                   count(*) AS movements
            FROM (
                SELECT k.to_location_id AS location_id, k.units AS in_units, 0 AS out_units
                FROM kardex k
                WHERE k.move_type IN ($IN_TYPES) AND k.moved_at >= :from AND k.moved_at < :to
                  AND k.to_location_id IS NOT NULL
                UNION ALL
                SELECT k.from_location_id, 0, k.units
                FROM kardex k
                WHERE k.move_type IN ($OUT_TYPES) AND k.moved_at >= :from AND k.moved_at < :to
                  AND k.from_location_id IS NOT NULL
            ) x
            JOIN locations l ON l.id = x.location_id
            GROUP BY l.id, l.hall, l.code, l.position, l.kind
            ORDER BY l.hall, l.code, l.position
            """.trimIndent(),
            params,
        ) { rs, _ ->
            val location = Location(
                id = 0,
                warehouseId = 0,
                hall = rs.getString("hall"),
                code = rs.getString("code"),
                position = rs.getString("position"),
                kind = LocationKind.valueOf(rs.getString("kind")),
            )
            SummaryLine(
                key = location.displayName,
                keyLabel = location.displayName,
                inUnits = rs.getBigDecimal("in_units").toQuantity(),
                outUnits = rs.getBigDecimal("out_units").toQuantity(),
                movements = rs.getInt("movements"),
            )
        }
    }

    /**
     * La reconciliación: **solo los productos descuadrados**.
     *
     * La comparación la hace la función de la base (`comprobar_proyecciones`), que es
     * la que conoce el ledger y la proyección; el servidor no recalcula el veredicto
     * en Kotlin, porque entonces habría dos verdades y el descuadre sería el de la
     * pantalla.
     */
    override fun divergences(): List<Divergence> =
        query(
            """
            SELECT product_id, product_code, projected_total, ledger_total, difference
            FROM comprobar_proyecciones() ORDER BY product_code
            """.trimIndent(),
            emptyMap(),
        ) { rs, _ ->
            Divergence(
                productId = rs.getLong("product_id"),
                productCode = rs.getString("product_code"),
                projectedTotal = rs.getBigDecimal("projected_total").toQuantity(),
                ledgerTotal = rs.getBigDecimal("ledger_total").toQuantity(),
                difference = rs.getBigDecimal("difference").toQuantity(),
            )
        }

    override fun kardexChunk(filter: KardexFilter, offset: Int, chunkSize: Int): List<KardexLine> =
        // La misma consulta paginada del inventario: el trozo N es la página N con el
        // tamaño del trozo, así que no hay una segunda forma de leer el kardex.
        kardexPages.page(filter, PageRequest.of(page = offset / chunkSize, size = chunkSize)).items

    // ── Interno ─────────────────────────────────────────────────────────────

    /** El `WHERE` y sus parámetros, compartidos por la página y los totales. */
    private fun inventoryWhere(filter: InventoryReportFilter): Pair<List<String>, Map<String, Any?>> {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()
        filter.productCode?.let {
            where += "p.product_code = :productCode"
            params["productCode"] = it.value
        }
        filter.warehouseCode?.let {
            where += "w.code = :warehouseCode"
            params["warehouseCode"] = it
        }
        if (filter.onlyWithStock) where += "s.quantity > 0"
        return where to params
    }

    private fun clauseOf(where: List<String>): String =
        if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")

    /** El rango de fechas del resumen: `to` incluye el día operativo entero. */
    private fun rangeParams(filter: MovementSummaryFilter): Map<String, Any?> = mapOf(
        "from" to filter.from.atStartOfDayIn(businessZone),
        "to" to filter.to.plusDays(1).atStartOfDayIn(businessZone),
        "zone" to businessZone.id,
    )

    private fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet, Int) -> T): List<T> {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.query(mapper).list()
    }

    private fun ResultSet.toStockLine() = StockLine(
        productId = getLong("product_id"),
        productCode = getString("product_code"),
        productName = getString("product_name"),
        locationId = getLong("location_id"),
        locationDisplayName = locationOf().displayName,
        warehouseId = getLong("warehouse_id"),
        warehouseCode = getString("warehouse_code"),
        quantity = getBigDecimal("quantity").toQuantity(),
    )

    private fun ResultSet.locationOf() = Location(
        id = getLong("location_id"),
        warehouseId = getLong("warehouse_id"),
        hall = getString("location_hall"),
        code = getString("location_code"),
        position = getString("location_position"),
        kind = LocationKind.valueOf(getString("location_kind")),
    )

    private companion object {
        const val INVENTORY_COLUMNS =
            "SELECT s.product_id, p.product_code, p.name AS product_name, " +
                "s.location_id, l.hall AS location_hall, l.code AS location_code, " +
                "l.position AS location_position, l.kind::text AS location_kind, " +
                "s.warehouse_id, w.code AS warehouse_code, s.quantity"

        const val INVENTORY_FROM =
            "FROM stock_by_location s " +
                "JOIN products p ON p.id = s.product_id " +
                "JOIN locations l ON l.id = s.location_id " +
                "JOIN warehouses w ON w.id = s.warehouse_id"

        /** Los movimientos que **suman** al total del producto. */
        const val IN_TYPES = "'INITIAL', 'RECEIPT_IN', 'ADJUSTMENT_IN', 'TRANSFER_IN'"

        /**
         * Los que **restan**. Ni `LOCATE` ni `MOVE_WITHIN_WAREHOUSE` están aquí: son
         * traslados internos y el total no cambia. `PENDING_OUT` tampoco: es una
         * intención, no una salida.
         */
        const val OUT_TYPES = "'FULFILLMENT_OUT', 'ADJUSTMENT_OUT', 'TRANSFER_OUT'"
    }
}
