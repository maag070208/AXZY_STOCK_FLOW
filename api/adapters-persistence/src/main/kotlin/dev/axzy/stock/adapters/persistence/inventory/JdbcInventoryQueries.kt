package dev.axzy.stock.adapters.persistence.inventory

import dev.axzy.stock.adapters.persistence.shared.atStartOfDayIn
import dev.axzy.stock.adapters.persistence.shared.toQuantity
import dev.axzy.stock.application.inventory.KardexFilter
import dev.axzy.stock.application.inventory.KardexLine
import dev.axzy.stock.application.inventory.KardexQueries
import dev.axzy.stock.application.inventory.LocationRef
import dev.axzy.stock.application.inventory.StockFilter
import dev.axzy.stock.application.inventory.StockLine
import dev.axzy.stock.application.inventory.StockQueries
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.SortDirection
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.value.Quantity
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.sql.ResultSet

/**
 * The stock and stock-ledger queries.
 *
 * Two statements per listing —the page and the count— **with the same filters**: if
 * the count ignored them, the envelope would say one thing and the `items` another
 * (docs/api/convenciones.md §1.4). The budget is measured in
 * `InventoryPersistenceTest`.
 *
 * The visible name of each location is composed by the domain from the
 * columns ([Location.displayName]); no `hall-code-position` is concatenated here.
 */
class JdbcInventoryQueries(
    private val jdbc: JdbcClient,
    /** The business time zone: date filters are operating days, not UTC days. */
    private val businessZone: ZoneId,
) : StockQueries, KardexQueries {

    override fun page(filter: StockFilter, request: PageRequest): Page<StockLine> {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()

        filter.productCode?.let {
            where += "p.product_code = :productCode"
            params["productCode"] = it.value
        }
        locationPredicate(filter.location, "l", where, params)
        // A listing with thousands of rows at zero is not read by anyone: it is on by default.
        if (filter.onlyWithStock) where += "s.quantity > 0"

        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val total = query(
            "SELECT count(*) FROM stock_by_location s " +
                "JOIN products p ON p.id = s.product_id " +
                "JOIN locations l ON l.id = s.location_id " +
                "JOIN warehouses w ON w.id = s.warehouse_id $clause",
            params,
        ) { rs, _ -> rs.getLong(1) }.single()

        val direction = if (request.sort.direction == SortDirection.DESC) "DESC" else "ASC"
        val items = query(
            "$STOCK_COLUMNS $STOCK_FROM $clause " +
                "ORDER BY p.product_code $direction, l.hall, l.code, l.position, s.location_id " +
                "LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toStockLine() }

        return Page(items, request.page, request.size, total)
    }

    override fun page(filter: KardexFilter, request: PageRequest): Page<KardexLine> {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()

        filter.productCode?.let {
            where += "p.product_code = :productCode"
            params["productCode"] = it.value
        }
        filter.moveType?.let {
            where += "k.move_type = CAST(:moveType AS movement_type)"
            params["moveType"] = it.name
        }
        filter.from?.let {
            where += "k.moved_at >= :from"
            params["from"] = it.atStartOfDayIn(businessZone)
        }
        filter.to?.let {
            // `to` includes the whole day: the instant is the start of the next day.
            where += "k.moved_at < :to"
            params["to"] = it.plusDays(1).atStartOfDayIn(businessZone)
        }

        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val total = query("SELECT count(*) $KARDEX_FROM $clause", params) { rs, _ -> rs.getLong(1) }.single()

        val items = query(
            "$KARDEX_COLUMNS $KARDEX_FROM $clause " +
                "ORDER BY ${orderBy(request)} LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toKardexLine() }

        return Page(items, request.page, request.size, total)
    }

    // ── Interno ─────────────────────────────────────────────────────────────

    /**
     * The predicate of a location filter, from the visible name.
     *
     * `RECEIVING` and `UNASSIGNED` are location **types**, not columns: filtering them
     * by `kind` finds them even if someone renames the receiving location.
     */
    private fun locationPredicate(
        ref: LocationRef?,
        alias: String,
        where: MutableList<String>,
        params: MutableMap<String, Any?>,
    ) {
        when (ref) {
            null -> Unit
            is LocationRef.Shelf -> {
                where += "($alias.hall = :hall AND $alias.code = :locationCode AND $alias.position = :position)"
                params["hall"] = ref.hall
                params["locationCode"] = ref.code
                params["position"] = ref.position
            }
            LocationRef.Receiving -> where += "$alias.kind = 'RECEIVING'"
            LocationRef.Unassigned -> where += "$alias.kind = 'UNASSIGNED'"
        }
    }

    private fun orderBy(request: PageRequest): String {
        val direction = if (request.sort.direction == SortDirection.DESC) "DESC" else "ASC"
        return when (request.sort.field) {
            "productCode" -> "p.product_code $direction, k.moved_at DESC, k.id DESC"
            "moveType" -> "k.move_type $direction, k.moved_at DESC, k.id DESC"
            // By default, the most recent first: it is what people want to look at.
            else -> "k.moved_at $direction, k.id $direction"
        }
    }

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
        locationDisplayName = locationOf("location_").displayName,
        warehouseId = getLong("warehouse_id"),
        warehouseCode = getString("warehouse_code"),
        quantity = getBigDecimal("quantity").toQuantity(),
    )

    private fun ResultSet.toKardexLine() = KardexLine(
        id = getLong("id"),
        movedAt = getObject("moved_at", OffsetDateTime::class.java).toInstant(),
        productCode = getString("product_code"),
        productName = getString("product_name"),
        moveType = getString("move_type"),
        units = getBigDecimal("units").toQuantity(),
        quantityAfter = getBigDecimal("quantity_after").toQuantity(),
        fromLocationDisplayName = locationOrNull("from_")?.displayName,
        toLocationDisplayName = locationOrNull("to_")?.displayName,
        container = getString("container"),
        orderNumber = getString("order_number"),
        adjustmentReason = getString("adjustment_reason"),
        adjustmentOrigin = getString("adjustment_origin"),
        userId = getLong("user_id").takeUnless { wasNull() },
        userName = getString("user_name"),
        note = getString("note"),
    )

    /** The location of the `JOIN`, with the prefix of its columns (`location_`, `from_`, `to_`). */
    private fun ResultSet.locationOf(prefix: String): Location = Location(
        id = getLong("${prefix}id"),
        warehouseId = 0,
        hall = getString("${prefix}hall"),
        code = getString("${prefix}code"),
        position = getString("${prefix}position"),
        kind = LocationKind.valueOf(getString("${prefix}kind")),
    )

    private fun ResultSet.locationOrNull(prefix: String): Location? =
        if (getString("${prefix}id") == null) null else locationOf(prefix)

    private companion object {
        const val STOCK_COLUMNS =
            "SELECT s.product_id, p.product_code, p.name AS product_name, " +
                "l.hall AS location_hall, l.code AS location_code, " +
                "l.position AS location_position, l.kind::text AS location_kind, " +
                "s.location_id, s.warehouse_id, w.code AS warehouse_code, s.quantity"

        const val STOCK_FROM =
            "FROM stock_by_location s " +
                "JOIN products p ON p.id = s.product_id " +
                "JOIN locations l ON l.id = s.location_id " +
                "JOIN warehouses w ON w.id = s.warehouse_id"

        const val KARDEX_COLUMNS =
            "SELECT k.id, k.moved_at, p.product_code, p.name AS product_name, " +
                "k.move_type::text AS move_type, k.units, k.quantity_after, " +
                "fl.id AS from_id, fl.hall AS from_hall, fl.code AS from_code, " +
                "fl.position AS from_position, fl.kind::text AS from_kind, " +
                "tl.id AS to_id, tl.hall AS to_hall, tl.code AS to_code, " +
                "tl.position AS to_position, tl.kind::text AS to_kind, " +
                "k.container, k.order_number, k.adjustment_reason::text AS adjustment_reason, " +
                "k.adjustment_origin::text AS adjustment_origin, k.user_id, u.name AS user_name, k.note"

        const val KARDEX_FROM =
            "FROM kardex k " +
                "JOIN products p ON p.id = k.product_id " +
                "LEFT JOIN locations fl ON fl.id = k.from_location_id " +
                "LEFT JOIN locations tl ON tl.id = k.to_location_id " +
                "LEFT JOIN users u ON u.id = k.user_id"
    }
}
