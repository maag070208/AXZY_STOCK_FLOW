package dev.axzy.stock.adapters.persistence.catalog

import dev.axzy.stock.application.catalog.NewProduct
import dev.axzy.stock.application.catalog.ProductFilter
import dev.axzy.stock.application.catalog.ProductView
import dev.axzy.stock.application.catalog.Products
import dev.axzy.stock.adapters.persistence.shared.longOrNull
import dev.axzy.stock.application.catalog.UpsertSummary
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.SortDirection
import dev.axzy.stock.domain.catalog.Product
import dev.axzy.stock.domain.catalog.ValidatedProduct
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.ResultSet

/**
 * Los productos sobre PostgreSQL.
 *
 * El saldo sale de `product_stock`, **la proyección**, no de sumar el kardex
 * (regla R-P3): es el mismo número que mantiene el motor de inventario, pero sin
 * recorrer el historial.
 */
class JdbcProducts(private val jdbc: JdbcClient, private val json: JsonMapper) : Products {

    override fun findById(id: Long): ProductView? =
        query("SELECT $COLUMNS $JOINS WHERE p.id = :id", mapOf("id" to id)) { rs, _ -> rs.toView() }
            .firstOrNull()

    override fun findByCode(code: ProductCode): Product? =
        query(
            "SELECT $COLUMNS $JOINS WHERE p.product_code = :code",
            mapOf("code" to code.value),
        ) { rs, _ -> rs.toProduct() }.firstOrNull()

    override fun stockOf(productId: Long): Quantity =
        query("SELECT quantity FROM product_stock WHERE product_id = :id", mapOf("id" to productId)) { rs, _ ->
            rs.getBigDecimal("quantity")
        }.firstOrNull().toQuantity()

    override fun page(filter: ProductFilter, request: PageRequest): Page<ProductView> {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()

        filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where += "(p.product_code ILIKE :q OR p.name ILIKE :q)"
            params["q"] = "%$it%"
        }
        filter.active?.let {
            where += "p.active = :active"
            params["active"] = it
        }
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")

        // El conteo usa LOS MISMOS filtros que el listado: si los ignorara, el sobre
        // diría una cosa y los `items` otra (docs/api/convenciones.md §1.4).
        val total = query("SELECT count(*) FROM products p $clause", params) { rs, _ -> rs.getLong(1) }.single()

        val items = query(
            "SELECT $COLUMNS $JOINS $clause " +
                "ORDER BY ${orderBy(request)} LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toView() }

        return Page(items, request.page, request.size, total)
    }

    override fun insert(newProduct: NewProduct): ProductView {
        val id = query(
            """
            INSERT INTO products (product_code, name, description, unit_measure_id, external_id)
            VALUES (:code, :name, :description, :unit, :externalId)
            RETURNING id
            """.trimIndent(),
            mapOf(
                "code" to newProduct.code.value,
                "name" to newProduct.name,
                "description" to newProduct.description,
                "unit" to newProduct.unitMeasureId,
                "externalId" to newProduct.externalId,
            ),
        ) { rs, _ -> rs.getLong("id") }.single()
        return requireNotNull(findById(id)) { "El producto recién insertado $id no se pudo leer" }
    }

    override fun update(product: Product): ProductView {
        query(
            """
            UPDATE products
            SET product_code = :code, name = :name, description = :description,
                unit_measure_id = :unit, active = :active, updated_at = now()
            WHERE id = :id
            RETURNING id
            """.trimIndent(),
            mapOf(
                "id" to product.id,
                "code" to product.code.value,
                "name" to product.name,
                "description" to product.description,
                "unit" to product.unitMeasureId,
                "active" to product.active,
            ),
        ) { rs, _ -> rs.getLong("id") }.single()
        return requireNotNull(findById(product.id)) { "El producto ${product.id} no se pudo leer tras actualizarlo" }
    }

    /**
     * El alta masiva de la importación: **una sentencia para todo el archivo**.
     *
     * El archivo viaja como un documento JSON y la base lo expande con
     * `jsonb_to_recordset` (regla R-P9). Una sentencia por producto serían 1.398
     * idas y vueltas para un archivo que se importa de una vez.
     *
     * El `WHERE` del `DO UPDATE` es lo que distingue «actualizado» de «ya estaba
     * igual»: sin él, la segunda importación diría «1.398 actualizados» cuando no
     * cambió ninguno (K2).
     */
    override fun upsertAll(products: List<ValidatedProduct>): UpsertSummary {
        if (products.isEmpty()) return UpsertSummary(0, 0, 0)

        val payload = json.writeValueAsString(
            products.map {
                mapOf(
                    "product_code" to it.code.value,
                    "name" to it.name,
                    "description" to null,
                    "unit_measure" to it.unitMeasure,
                    "external_id" to it.externalId?.toLong(),
                )
            },
        )

        val inserted = query(UPSERT, mapOf("payload" to payload)) { rs, _ -> rs.getBoolean("inserted") }
        val created = inserted.count { it }
        val updated = inserted.size - created
        return UpsertSummary(created = created, updated = updated, skipped = products.size - inserted.size)
    }

    // ── Interno ─────────────────────────────────────────────────────────────

    private fun orderBy(request: PageRequest): String {
        val column = when (request.sort.field) {
            "productCode" -> "p.product_code"
            "name" -> "p.name"
            "createdAt" -> "p.created_at"
            else -> "p.product_code"
        }
        val direction = if (request.sort.direction == SortDirection.DESC) "DESC" else "ASC"
        // Se añade el id como desempate: sin él, dos productos con el mismo nombre
        // pueden salir en orden distinto entre páginas y perderse o repetirse.
        return "$column $direction, p.id ASC"
    }

    private fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet, Int) -> T): List<T> {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.query(mapper).list()
    }

    private fun ResultSet.toView() = ProductView(toProduct(), getBigDecimal("stock").toQuantity())

    private fun ResultSet.toProduct() = Product(
        id = getLong("id"),
        code = ProductCode.of(getString("product_code")),
        name = getString("name"),
        description = getString("description"),
        unitMeasureId = getLong("unit_measure_id"),
        unitMeasure = getString("unit_measure"),
        externalId = longOrNull("external_id"),
        active = getBoolean("active"),
    )

    private companion object {
        /**
         * La proyección del saldo va en la misma consulta que el listado: resolverla
         * con una consulta por producto sería el N+1 que la regla R-P2 prohíbe.
         */
        const val COLUMNS =
            "p.id, p.product_code, p.name, p.description, p.unit_measure_id, " +
                "u.code AS unit_measure, p.external_id, p.active, COALESCE(ps.quantity, 0) AS stock"

        const val JOINS =
            "FROM products p " +
                "JOIN units_of_measure u ON u.id = p.unit_measure_id " +
                "LEFT JOIN product_stock ps ON ps.product_id = p.id"

        val UPSERT =
            """
            WITH incoming AS (
                SELECT r.product_code, r.name, r.description, r.external_id, u.id AS unit_measure_id
                FROM jsonb_to_recordset(CAST(:payload AS jsonb)) AS r(
                    product_code TEXT, name TEXT, description TEXT, unit_measure TEXT, external_id BIGINT
                )
                JOIN units_of_measure u ON u.code = r.unit_measure
            ), upserted AS (
                INSERT INTO products (product_code, name, description, unit_measure_id, external_id)
                SELECT product_code, name, description, unit_measure_id, external_id FROM incoming
                ON CONFLICT (product_code) DO UPDATE
                SET name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    unit_measure_id = EXCLUDED.unit_measure_id,
                    external_id = EXCLUDED.external_id,
                    updated_at = now()
                WHERE products.name IS DISTINCT FROM EXCLUDED.name
                   OR products.description IS DISTINCT FROM EXCLUDED.description
                   OR products.unit_measure_id IS DISTINCT FROM EXCLUDED.unit_measure_id
                   OR products.external_id IS DISTINCT FROM EXCLUDED.external_id
                RETURNING (xmax = 0) AS inserted
            )
            SELECT inserted FROM upserted
            """.trimIndent()
    }
}

/** `NUMERIC(14,3)` a la cantidad del dominio, que va en milésimas. */
internal fun BigDecimal?.toQuantity(): Quantity =
    this?.setScale(Quantity.SCALE)?.movePointRight(Quantity.SCALE)?.longValueExact()?.let(Quantity::ofScaled)
        ?: Quantity.ZERO
