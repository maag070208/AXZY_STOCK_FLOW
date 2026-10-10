package dev.axzy.stock.adapters.persistence.catalog

import dev.axzy.stock.application.catalog.NewWarehouse
import dev.axzy.stock.application.catalog.Warehouses
import dev.axzy.stock.domain.catalog.Warehouse
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet

/**
 * The warehouses on PostgreSQL.
 *
 * `clearDefault` and the `UPDATE` that sets the new default go in the same
 * transaction: the partial index `warehouses_one_default_idx` does not allow two, so
 * that between the two statements none can be left.
 */
class JdbcWarehouses(private val jdbc: JdbcClient) : Warehouses {

    override fun list(query: String?, active: Boolean?): List<Warehouse> {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()

        query?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where += "(code ILIKE :q OR name ILIKE :q)"
            params["q"] = "%$it%"
        }
        active?.let {
            where += "active = :active"
            params["active"] = it
        }
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")

        // Without pagination and without a cap: it is a closed and tiny catalog that the
        // interfaz necesita completo (docs/api/convenciones.md §1.4).
        return query("$SELECT $clause ORDER BY is_default DESC, code ASC", params) { rs, _ -> rs.toWarehouse() }
    }

    override fun findById(id: Long): Warehouse? =
        query("$SELECT WHERE id = :id", mapOf("id" to id)) { rs, _ -> rs.toWarehouse() }.firstOrNull()

    override fun findByCode(code: String): Warehouse? =
        query("$SELECT WHERE code = :code", mapOf("code" to code)) { rs, _ -> rs.toWarehouse() }.firstOrNull()

    override fun defaultWarehouse(): Warehouse? =
        query("$SELECT WHERE is_default AND active LIMIT 1", emptyMap()) { rs, _ -> rs.toWarehouse() }.firstOrNull()

    override fun insert(newWarehouse: NewWarehouse): Warehouse {
        val id = query(
            """
            INSERT INTO warehouses (code, name, address, is_default)
            VALUES (:code, :name, :address, :isDefault)
            RETURNING id
            """.trimIndent(),
            mapOf(
                "code" to newWarehouse.code,
                "name" to newWarehouse.name,
                "address" to newWarehouse.address,
                "isDefault" to newWarehouse.isDefault,
            ),
        ) { rs, _ -> rs.getLong("id") }.single()
        return requireNotNull(findById(id)) { "The newly inserted warehouse $id could not be read back" }
    }

    override fun update(warehouse: Warehouse): Warehouse {
        query(
            """
            UPDATE warehouses
            SET code = :code, name = :name, address = :address, is_default = :isDefault, updated_at = now()
            WHERE id = :id
            RETURNING id
            """.trimIndent(),
            mapOf(
                "id" to warehouse.id,
                "code" to warehouse.code,
                "name" to warehouse.name,
                "address" to warehouse.address,
                "isDefault" to warehouse.isDefault,
            ),
        ) { rs, _ -> rs.getLong("id") }.single()
        return requireNotNull(findById(warehouse.id)) { "Warehouse ${warehouse.id} could not be read back after updating it" }
    }

    override fun clearDefault() {
        jdbc.sql("UPDATE warehouses SET is_default = FALSE, updated_at = now() WHERE is_default").update()
    }

    private fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet, Int) -> T): List<T> {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.query(mapper).list()
    }

    private fun ResultSet.toWarehouse() = Warehouse(
        id = getLong("id"),
        code = getString("code"),
        name = getString("name"),
        address = getString("address"),
        isDefault = getBoolean("is_default"),
        active = getBoolean("active"),
    )

    private companion object {
        const val SELECT = "SELECT id, code, name, address, is_default, active FROM warehouses"
    }
}
