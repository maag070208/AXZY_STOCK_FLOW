package dev.axzy.stock.adapters.persistence.catalog

import dev.axzy.stock.application.catalog.LocationFilter
import dev.axzy.stock.application.catalog.LocationView
import dev.axzy.stock.application.catalog.Locations
import dev.axzy.stock.application.catalog.NewLocation
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.SortDirection
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.value.Quantity
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet

/**
 * Las ubicaciones sobre PostgreSQL.
 *
 * El nombre visible (`displayName`) **no se guarda**: se compone en el dominio a
 * partir de `hall`, `code` y `position`, que es lo que hace imposible que la
 * etiqueta del estante y el nombre que muestra la API se separen.
 */
class JdbcLocations(private val jdbc: JdbcClient) : Locations {

    override fun findById(id: Long): LocationView? =
        query("$SELECT FROM locations l JOIN warehouses w ON w.id = l.warehouse_id WHERE l.id = :id", mapOf("id" to id)) { rs, _ ->
            rs.toView()
        }.firstOrNull()

    override fun find(warehouseId: Long, hall: String, code: String, position: String): LocationView? =
        query(
            "$SELECT FROM locations l JOIN warehouses w ON w.id = l.warehouse_id " +
                "WHERE l.warehouse_id = :warehouseId AND l.hall = :hall AND l.code = :code AND l.position = :position",
            mapOf("warehouseId" to warehouseId, "hall" to hall, "code" to code, "position" to position),
        ) { rs, _ -> rs.toView() }.firstOrNull()

    override fun receivingOf(warehouseId: Long): LocationView? =
        query(
            "$SELECT FROM locations l JOIN warehouses w ON w.id = l.warehouse_id " +
                "WHERE l.warehouse_id = :warehouseId AND l.kind = 'RECEIVING' LIMIT 1",
            mapOf("warehouseId" to warehouseId),
        ) { rs, _ -> rs.toView() }.firstOrNull()

    override fun stockOf(locationId: Long): Quantity =
        query(
            "SELECT COALESCE(SUM(quantity), 0) AS quantity FROM stock_by_location WHERE location_id = :id",
            mapOf("id" to locationId),
        ) { rs, _ -> rs.getBigDecimal("quantity") }.firstOrNull().toQuantity()

    override fun page(filter: LocationFilter, request: PageRequest): Page<LocationView> {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()

        filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where += "(l.hall ILIKE :q OR l.code ILIKE :q OR l.position ILIKE :q)"
            params["q"] = "%$it%"
        }
        filter.warehouseId?.let {
            where += "l.warehouse_id = :warehouseId"
            params["warehouseId"] = it
        }
        filter.kind?.let {
            where += "l.kind = CAST(:kind AS location_kind)"
            params["kind"] = it.name
        }
        filter.active?.let {
            where += "l.active = :active"
            params["active"] = it
        }
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")

        val total = query("SELECT count(*) FROM locations l $clause", params) { rs, _ -> rs.getLong(1) }.single()
        val items = query(
            "$SELECT FROM locations l JOIN warehouses w ON w.id = l.warehouse_id $clause " +
                "ORDER BY ${orderBy(request)} LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toView() }

        return Page(items, request.page, request.size, total)
    }

    override fun insert(newLocation: NewLocation): LocationView {
        val id = query(
            """
            INSERT INTO locations (warehouse_id, hall, code, position, levels, kind)
            VALUES (:warehouseId, :hall, :code, :position, :levels, CAST(:kind AS location_kind))
            RETURNING id
            """.trimIndent(),
            mapOf(
                "warehouseId" to newLocation.warehouseId,
                "hall" to newLocation.hall,
                "code" to newLocation.code,
                "position" to newLocation.position,
                "levels" to newLocation.levels,
                "kind" to newLocation.kind.name,
            ),
        ) { rs, _ -> rs.getLong("id") }.single()
        return requireNotNull(findById(id)) { "La ubicación recién insertada $id no se pudo leer" }
    }

    override fun update(location: Location): LocationView {
        query(
            """
            UPDATE locations
            SET hall = :hall, code = :code, position = :position, levels = :levels,
                kind = CAST(:kind AS location_kind), active = :active, updated_at = now()
            WHERE id = :id
            RETURNING id
            """.trimIndent(),
            mapOf(
                "id" to location.id,
                "hall" to location.hall,
                "code" to location.code,
                "position" to location.position,
                "levels" to location.levels,
                "kind" to location.kind.name,
                "active" to location.active,
            ),
        ) { rs, _ -> rs.getLong("id") }.single()
        return requireNotNull(findById(location.id)) { "La ubicación ${location.id} no se pudo leer tras actualizarla" }
    }

    // ── Interno ─────────────────────────────────────────────────────────────

    private fun orderBy(request: PageRequest): String {
        val direction = if (request.sort.direction == SortDirection.DESC) "DESC" else "ASC"
        val column = when (request.sort.field) {
            "hall" -> "l.hall"
            "code" -> "l.code"
            "position" -> "l.position"
            "createdAt" -> "l.created_at"
            else -> "l.hall"
        }
        /*
         * Se ordena por las tres partes y por el id, no por una sola: dos ubicaciones
         * del mismo pasillo con el campo de orden repetido pueden salir en orden
         * distinto entre páginas, y entonces una fila se repite y otra desaparece.
         */
        return "$column $direction, l.hall ASC, l.code ASC, l.position ASC, l.id ASC"
    }

    private fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet, Int) -> T): List<T> {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.query(mapper).list()
    }

    private fun ResultSet.toView() = LocationView(
        location = Location(
            id = getLong("id"),
            warehouseId = getLong("warehouse_id"),
            hall = getString("hall"),
            code = getString("code"),
            position = getString("position"),
            levels = getInt("levels"),
            kind = LocationKind.valueOf(getString("kind")),
            active = getBoolean("active"),
        ),
        warehouseCode = getString("warehouse_code"),
    )

    private companion object {
        const val SELECT =
            "SELECT l.id, l.warehouse_id, l.hall, l.code, l.position, l.levels, " +
                "l.kind::text AS kind, l.active, w.code AS warehouse_code"
    }
}
