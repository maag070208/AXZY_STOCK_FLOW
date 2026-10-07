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
object CatalogoTestDb {

    private const val HOST = "127.0.0.1"
    private const val PUERTO = "5432"

    val usuario: String = System.getenv("STOCK_TEST_DB_USER") ?: "stock_app"
    val contrasena: String = System.getenv("STOCK_TEST_DB_PASSWORD") ?: contrasenaLocal()

    private fun contrasenaLocal(): String {
        val archivo: File? = listOf(File("infra/.env"), File("../infra/.env"))
            .firstOrNull { it.exists() }

        return archivo?.readLines()
            ?.firstOrNull { it.startsWith("POSTGRES_PASSWORD=") }
            ?.substringAfter("=")
            ?.trim()
            ?: ""
    }

    fun config(nombreBase: String) = DatabaseConfig(
        jdbcUrl = "jdbc:postgresql://$HOST:$PUERTO/$nombreBase",
        username = usuario,
        password = contrasena,
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
    fun problemaDeConexion(): String? = try {
        conPool("postgres") { pool ->
            pool.connection.use { conexion ->
                conexion.createStatement().use { sentencia ->
                    sentencia.executeQuery("SELECT 1").use { filas -> filas.next() }
                }
            }
        }
        null
    } catch (e: Exception) {
        "${e::class.simpleName}: ${e.message}"
    }

    /** Crea un pool, ejecuta el bloque y lo cierra siempre. */
    fun <T> conPool(nombreBase: String, bloque: (DataSource) -> T): T {
        val pool = buildDataSource(config(nombreBase))
        return try {
            bloque(pool)
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
    fun recrear(nombreBase: String) {
        conPool("postgres") { pool ->
            pool.connection.use { conexion ->
                conexion.autoCommit = true
                conexion.createStatement().use { sentencia ->
                    // `WITH (FORCE)` cierra las conexiones abiertas: sin esto, un
                    // pool que quedó vivo impide soltar la base.
                    sentencia.execute("DROP DATABASE IF EXISTS $nombreBase WITH (FORCE)")
                    sentencia.execute("CREATE DATABASE $nombreBase")
                }
            }
        }

        conPool(nombreBase) { pool -> migrate(pool) }
    }

    /**
     * Deja el catálogo como recién migrado, conservando las semillas.
     *
     * Se borran las tablas de inventario antes que los productos porque las
     * referencian. `kardex` no se toca: es append-only y su trigger prohíbe el
     * `DELETE` —y este módulo no escribe en él.
     */
    fun limpiar(pool: DataSource) {
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
        ).forEach { sql -> ejecutar(pool, sql) }
    }

    /** Ejecuta una sentencia suelta (fixtures de los tests) con confirmación. */
    fun ejecutar(pool: DataSource, sql: String, vararg parametros: Any?) {
        pool.connection.use { conexion ->
            conexion.autoCommit = true
            conexion.prepareStatement(sql).use { sentencia ->
                parametros.forEachIndexed { indice, valor -> sentencia.setObject(indice + 1, valor) }
                sentencia.execute()
            }
        }
    }

    fun consultarUnLong(pool: DataSource, sql: String, vararg parametros: Any?): Long =
        pool.connection.use { conexion ->
            conexion.autoCommit = true
            conexion.prepareStatement(sql).use { sentencia ->
                parametros.forEachIndexed { indice, valor -> sentencia.setObject(indice + 1, valor) }
                sentencia.executeQuery().use { filas ->
                    filas.next()
                    filas.getLong(1)
                }
            }
        }

    fun contarProductos(pool: DataSource): Long =
        consultarUnLong(pool, "SELECT count(*) FROM products")

    fun contarProductosActivos(pool: DataSource): Long =
        consultarUnLong(pool, "SELECT count(*) FROM products WHERE active")

    /** Inserta un producto directamente, con `external_id` si se pide. */
    fun insertarProducto(
        pool: DataSource,
        codigo: String,
        nombre: String,
        externalId: Long? = null,
        activo: Boolean = true,
    ) {
        ejecutar(
            pool,
            """
            INSERT INTO products (product_code, name, unit_measure_id, external_id, active)
            SELECT ?, ?, id, ?, ? FROM units_of_measure WHERE code = 'PZA'
            """.trimIndent(),
            codigo,
            nombre,
            externalId,
            activo,
        )
    }

    fun idDeProducto(pool: DataSource, codigo: String): Long =
        consultarUnLong(pool, "SELECT id FROM products WHERE product_code = ?", codigo)

    /** Ubicación de recepción sembrada por la migración: `(id, warehouse_id)`. */
    fun ubicacionDeRecepcion(pool: DataSource): Pair<Long, Long> {
        val ubicacionId = consultarUnLong(
            pool,
            "SELECT id FROM locations WHERE kind = 'RECEIVING' ORDER BY id LIMIT 1",
        )
        val bodegaId = consultarUnLong(pool, "SELECT warehouse_id FROM locations WHERE id = ?", ubicacionId)
        return ubicacionId to bodegaId
    }

    /** Existencias en una ubicación. Es el dato que bloquea el borrado lógico. */
    fun insertarStock(pool: DataSource, productoId: Long, ubicacionId: Long, bodegaId: Long, cantidad: String) {
        ejecutar(
            pool,
            """
            INSERT INTO stock_by_location (product_id, location_id, warehouse_id, quantity)
            VALUES (?, ?, ?, ?::numeric)
            """.trimIndent(),
            productoId,
            ubicacionId,
            bodegaId,
            cantidad,
        )
    }

    /** Mercancía en tránsito: cuenta en el total del producto. */
    fun insertarTransito(pool: DataSource, productoId: Long, bodegaOrigen: Long, bodegaDestino: Long, cantidad: String) {
        ejecutar(
            pool,
            """
            INSERT INTO stock_in_transit
                (product_id, from_warehouse_id, to_warehouse_id, quantity)
            VALUES (?, ?, ?, ?::numeric)
            """.trimIndent(),
            productoId,
            bodegaOrigen,
            bodegaDestino,
            cantidad,
        )
    }

    /** La bodega sembrada por la migración. */
    fun bodegaPrincipal(pool: DataSource): Long =
        consultarUnLong(pool, "SELECT id FROM warehouses WHERE code = 'PRINCIPAL'")

    /**
     * El CSV real extraído del Excel del cliente, que es el insumo del test de
     * cuadre del dominio (mismo archivo, misma ruta relativa).
     */
    fun archivoCsvReal(): File? = listOf(
        File("../stock-domain/src/commonTest/resources/catalogo_real.csv"),
        File("stock-domain/src/commonTest/resources/catalogo_real.csv"),
        File("src/test/resources/catalogo_real.csv"),
    ).firstOrNull { it.exists() }
}
