package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import java.io.File
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BASE DE DATOS REAL PARA LOS TESTS DE CATÁLOGO
 *
 * Los tests corren contra **PostgreSQL de verdad**, no contra un doble de
 * prueba. Un `LIMIT` mal puesto, un `CHECK` que rechaza el valor o una
 * violación de unicidad que el código no traduce solo se manifiestan contra el
 * motor real: con un mock, el test comprobaría que el mock hace lo que se le
 * dijo, que es exactamente lo que no se quiere verificar.
 *
 * Cada spec usa una **base dedicada** que se borra y se recrea entera. No se
 * toca `stock_test`, que es compartida: soltar el esquema de una base que otra
 * prueba está usando produce fallos que dependen del orden de ejecución, y ese
 * es el peor tipo de test.
 *
 * **Si no hay base disponible, los tests se saltan** en lugar de fallar o, peor,
 * pasar en falso.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object CatalogTestDb {

    private const val HOST = "127.0.0.1"
    private const val Port = "5432"

    val user: String = System.getenv("STOCK_TEST_DB_USER") ?: "stock_app"
    val password: String = System.getenv("STOCK_TEST_DB_PASSWORD") ?: passwordLocal()

    private fun passwordLocal(): String {
        val file: File? = listOf(File("infra/.env"), File("../infra/.env"))
            .firstOrNull { it.exists() }

        return file?.readLines()
            ?.firstOrNull { it.startsWith("POSTGRES_PASSWORD=") }
            ?.substringAfter("=")
            ?.trim()
            ?: ""
    }

    fun config(nameBase: String) = DatabaseConfig(
        jdbcUrl = "jdbc:postgresql://$HOST:$Port/$nameBase",
        username = user,
        password = password,
        maxPoolSize = 2,
        runMigrations = false,
    )

    private fun configAdmin() = config("postgres")

    /**
     * Diagnóstico de conexión. `null` si la base responde.
     *
     * Se ejecuta una consulta real, no solo se pide una conexión: HikariCP las
     * obtiene de forma perezosa y `pool.connection` devuelve un envoltorio sin
     * haber contactado con el servidor, así que la comprobación pasaría y el
     * primer `SELECT` de verdad fallaría.
     */
    fun connectionProblem(): String? = try {
        withPool("postgres") { pool ->
            pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { rows -> rows.next() }
                }
            }
        }
        null
    } catch (e: Exception) {
        "${e::class.simpleName}: ${e.message}"
    }

    /** Crea un pool, ejecuta el bloque y lo cierra siempre. */
    fun <T> withPool(nameBase: String, block: (DataSource) -> T): T {
        val pool = buildDataSource(config(nameBase))
        return try {
            block(pool)
        } finally {
            (pool as? AutoCloseable)?.close()
        }
    }

    /**
     * Borra y recrea la base dedicada y le aplica las migraciones.
     *
     * Se conecta a `postgres` porque no se puede soltar la base a la que estás
     * conectado, y se hace con `autoCommit = true`: con `autoCommit = false` el
     * `CREATE DATABASE` quedaría sin confirmar y al cerrar la conexión
     * PostgreSQL haría ROLLBACK, dejando la base sin crear mientras el `DROP`
     * sí se habría aplicado.
     */
    fun recreate(nameBase: String) {
        withPool("postgres") { pool ->
            pool.connection.use { connection ->
                connection.autoCommit = true
                connection.createStatement().use { statement ->
                    // `WITH (FORCE)` cierra las conexiones abiertas: sin esto, un
                    // pool que quedó vivo impide soltar la base.
                    statement.execute("DROP DATABASE IF EXISTS $nameBase WITH (FORCE)")
                    statement.execute("CREATE DATABASE $nameBase")
                }
            }
        }

        withPool(nameBase) { pool -> migrate(pool) }
    }

    /**
     * Deja el catálogo como recién migrado, conservando las semillas.
     *
     * Se borran las tablas de inventario antes que los productos porque las
     * referencian. `kardex` no se toca: es append-only y su trigger prohíbe el
     * `DELETE` —y este módulo no escribe en él.
     */
    fun clean(pool: DataSource) {
        listOf(
            "DELETE FROM stock_in_transit",
            "DELETE FROM stock_by_location",
            "DELETE FROM product_stock",
            "DELETE FROM products",
            // Se conserva EXACTAMENTE la ubicación de recepción sembrada y se
            // borra todo lo demás. Filtrar por `kind <> 'RECEIVING'` dejaría
            // viva cualquier ubicación de prueba a la que un test le cambiara
            // el tipo, y entonces los recuentos del siguiente test fallarían
            // por un dato que nadie ve.
            "DELETE FROM locations WHERE NOT (hall = 'RECEPCION' AND code = 'RECEPCION' " +
                "AND position = 'RECEPCION')",
            "DELETE FROM warehouses WHERE code <> 'PRINCIPAL'",
        ).forEach { sql -> execute(pool, sql) }
    }

    /** Ejecuta una sentencia suelta (fixtures de los tests) con confirmación. */
    fun execute(pool: DataSource, sql: String, vararg parameters: Any?) {
        pool.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.execute()
            }
        }
    }

    fun querySingleLong(pool: DataSource, sql: String, vararg parameters: Any?): Long =
        pool.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    fun countProducts(pool: DataSource): Long =
        querySingleLong(pool, "SELECT count(*) FROM products")

    fun countActiveProducts(pool: DataSource): Long =
        querySingleLong(pool, "SELECT count(*) FROM products WHERE active")

    /** Inserta un producto directamente, con `external_id` si se pide. */
    fun insertProduct(
        pool: DataSource,
        code: String,
        name: String,
        externalId: Long? = null,
        active: Boolean = true,
    ) {
        execute(
            pool,
            """
            INSERT INTO products (product_code, name, unit_measure_id, external_id, active)
            SELECT ?, ?, id, ?, ? FROM units_of_measure WHERE code = 'PZA'
            """.trimIndent(),
            code,
            name,
            externalId,
            active,
        )
    }

    fun productId(pool: DataSource, code: String): Long =
        querySingleLong(pool, "SELECT id FROM products WHERE product_code = ?", code)

    /** Ubicación de recepción sembrada por la migración: `(id, warehouse_id)`. */
    fun receivingLocation(pool: DataSource): Pair<Long, Long> {
        val locationId = querySingleLong(
            pool,
            "SELECT id FROM locations WHERE kind = 'RECEIVING' ORDER BY id LIMIT 1",
        )
        val warehouseId = querySingleLong(pool, "SELECT warehouse_id FROM locations WHERE id = ?", locationId)
        return locationId to warehouseId
    }

    /** Existencias en una ubicación. Es el dato que bloquea el borrado lógico. */
    fun insertStock(pool: DataSource, productId: Long, locationId: Long, warehouseId: Long, quantity: String) {
        execute(
            pool,
            """
            INSERT INTO stock_by_location (product_id, location_id, warehouse_id, quantity)
            VALUES (?, ?, ?, ?::numeric)
            """.trimIndent(),
            productId,
            locationId,
            warehouseId,
            quantity,
        )
    }

    /** Mercancía en tránsito: cuenta en el total del producto. */
    fun insertTransit(pool: DataSource, productId: Long, warehouseOrigin: Long, warehouseDestination: Long, quantity: String) {
        execute(
            pool,
            """
            INSERT INTO stock_in_transit
                (product_id, from_warehouse_id, to_warehouse_id, quantity)
            VALUES (?, ?, ?, ?::numeric)
            """.trimIndent(),
            productId,
            warehouseOrigin,
            warehouseDestination,
            quantity,
        )
    }

    /** La bodega sembrada por la migración. */
    fun warehouseMain(pool: DataSource): Long =
        querySingleLong(pool, "SELECT id FROM warehouses WHERE code = 'PRINCIPAL'")

    /**
     * El CSV real extraído del Excel del cliente, que es el insumo del test de
     * cuadre del dominio (mismo archivo, misma ruta relativa).
     */
    fun fileCsvReal(): File? = listOf(
        File("../stock-domain/src/commonTest/resources/catalogo_real.csv"),
        File("stock-domain/src/commonTest/resources/catalogo_real.csv"),
        File("src/test/resources/catalogo_real.csv"),
    ).firstOrNull { it.exists() }
}
