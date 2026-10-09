package dev.axzy.stock.backend.inventory

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL MAPPER DE LA RECONCILIACIÓN, CONTRA LA FUNCIÓN REAL
 *
 * **POR QUÉ EXISTE ESTE TEST, y es la lección de toda la corrección de §9.**
 *
 * El mapper lee las columnas **por nombre en una cadena**:
 *
 *     projectedTotal = getBigDecimal("projected_total").toPlainString()
 *
 * Eso **compila igual** si el `RETURNS TABLE` de `comprobar_proyecciones()` dice
 * `total_en_bd` en vez de `projected_total`. El compilador no puede verlo: para él
 * las dos cosas son una cadena. Y no falla al arrancar, ni al migrar, ni en los
 * 501 tests que había. **Un mapper mal escrito no rompe: miente.**
 *
 * De hecho ése era el estado real del proyecto: el contrato §9 publicaba
 * `totalEnBd`/`totalEsperado`/`diferencia` porque **eran los nombres de columna de
 * la función**, y nadie lo había notado porque ningún test leía esa función. Los
 * tests de rutas solo comprobaban que el endpoint existiera (un 401 sin token), y
 * eso pasa por encima del mapper.
 *
 * La migración `V8` renombró las columnas. Este test es lo que ata las dos
 * mitades: **crea una divergencia de verdad, ejecuta la función y exige que el DTO
 * salga con los valores**. Si alguien renombra una columna y no el mapper, aquí
 * sale rojo en vez de salir `null` en producción.
 *
 * ── POR QUÉ CONTRA POSTGRESQL DE VERDAD ────────────────────────────────────
 *
 * Con un doble de prueba, el test comprobaría que el doble devuelve lo que se le
 * dijo, que es justo lo que NO se quiere verificar. Lo que hay que verificar es
 * que el nombre de la columna de PostgreSQL coincide con el del mapper, y eso solo
 * lo sabe PostgreSQL.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class DivergenceMapperTest : DescribeSpec({

    val nameBase = "stock_divergence_test"

    fun passwordLocal(): String {
        val file: File? = listOf(File("infra/.env"), File("../infra/.env"))
            .firstOrNull { it.exists() }
        return file?.readLines()
            ?.firstOrNull { it.startsWith("POSTGRES_PASSWORD=") }
            ?.substringAfter("=")?.trim() ?: ""
    }

    val config = DatabaseConfig(
        jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/$nameBase",
        username = System.getenv("STOCK_TEST_DB_USER") ?: "stock_app",
        password = System.getenv("STOCK_TEST_DB_PASSWORD") ?: passwordLocal(),
        maxPoolSize = 2,
        runMigrations = false,
    )

    val configAdmin = config.copy(jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/postgres")

    fun close(pool: DataSource?) {
        runCatching { (pool as? AutoCloseable)?.close() }
    }

    /** Base disponible. Si no, el test se salta y lo dice. */
    val thereIsBase: Boolean = run {
        val pool = runCatching { buildDataSource(configAdmin) }.getOrNull() ?: return@run false
        try {
            pool.connection.use { c ->
                c.createStatement().use { st -> st.executeQuery("SELECT 1").use { it.next() } }
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            close(pool)
        }
    }

    if (!thereIsBase) {
        println("DivergenceMapperTest SALTADO: no hay PostgreSQL. cd infra && docker compose up -d")
    }

    /** Recrea la base y aplica TODAS las migraciones, la V8 incluida. */
    fun prepareDatabase(): DataSource? {
        val admin = runCatching { buildDataSource(configAdmin) }.getOrNull() ?: return null
        try {
            admin.connection.use { c ->
                c.autoCommit = true
                c.createStatement().use { st ->
                    st.execute("DROP DATABASE IF EXISTS $nameBase WITH (FORCE)")
                    st.execute("CREATE DATABASE $nameBase")
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            close(admin)
        }

        val pool = buildDataSource(config)
        return try {
            migrate(pool)
            pool
        } catch (e: Exception) {
            close(pool)
            throw e
        }
    }

    fun <T> withDatabase(block: (DataSource) -> T): T? {
        if (!thereIsBase) return null
        val pool = prepareDatabase() ?: return null
        return try {
            block(pool)
        } finally {
            close(pool)
        }
    }

    /**
     * Un producto con el saldo proyectado DESCUADRADO a propósito.
     *
     * `product_stock` es una proyección que **solo escriben las funciones del
     * motor** (`V6`), así que para fabricar un descuadre hay que escribirla a mano:
     * se registra un movimiento de 1000 y después se fuerza el total a 700. Es
     * exactamente el estado que este reporte existe para detectar, y es lo que hace
     * que `comprobar_proyecciones()` devuelva una fila en vez de cero.
     */
    fun prepareDivergence(pool: DataSource) {
        pool.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute("INSERT INTO products (product_code, name, unit_measure_id) VALUES ('DIV1','DIVERGENTE',1)")
                val unassigned = "SELECT id FROM locations WHERE kind='UNASSIGNED'"
                st.execute(
                    "SELECT registrar_movimiento(1,1,'INITIAL',1000,NULL,($unassigned)," +
                        "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                )
                // El descuadre, escrito por la puerta de atrás a propósito.
                st.execute("UPDATE product_stock SET quantity = 700 WHERE product_id = 1")
            }
        }
    }

    describe("el mapper de la reconciliación lee las columnas que la función devuelve") {

        it("una divergencia real se convierte en el DTO con los nombres en inglés") {
            withDatabase { pool ->
                prepareDivergence(pool)

                val service = InventoryService(pool, dev.axzy.stock.backend.infrastructure.outbound.persistence.audit.AuditWriter(pool))
                val divergences = service.divergences()

                // Lo primero: la divergencia EXISTE. Si la consulta no devolviera
                // nada, el test pasaría sin haber leído el mapper, que es el falso
                // verde que hay que evitar aquí.
                divergences.size shouldBe 1

                val dto = divergences.single()
                dto.productCode shouldBe "DIV1"

                // Los tres totales, leídos de las columnas renombradas en la V8.
                // Si el mapper volviera a `total_en_bd`, `getBigDecimal` daría
                // `null` y esto reventaría con un NullPointerException: el test
                // convierte un fallo silencioso en uno ruidoso.
                dto.projectedTotal shouldBe "700.000"
                dto.ledgerTotal shouldBe "1000.000"
                dto.difference shouldBe "-300.000"

                // Y no son cadenas vacías, que es el otro modo de mentir de un mapper.
                dto.projectedTotal shouldNotBe ""
                dto.ledgerTotal shouldNotBe ""
            }
        }

        it("un producto cuadrado NO sale en el informe: la función filtra, no solo calcula") {
            withDatabase { pool ->
                // Dos productos: `OK1` cuadrado y `DIV1` descuadrado a mano.
                prepareDivergence(pool)
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute("INSERT INTO products (product_code, name, unit_measure_id) VALUES ('OK1','CUADRA',1)")
                        val unassigned = "SELECT id FROM locations WHERE kind='UNASSIGNED'"
                        st.execute(
                            "SELECT registrar_movimiento(2,1,'INITIAL',1000,NULL,($unassigned)," +
                                "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                        )
                    }
                }

                val service = InventoryService(pool, dev.axzy.stock.backend.infrastructure.outbound.persistence.audit.AuditWriter(pool))
                val divergences = service.divergences()

                // **UNA sola fila**, y es el corazón del test. `comprobar_proyecciones()`
                // lleva un `WHERE` que descarta los productos que cuadran, así que el
                // informe trae SOLO los descuadres. Si el `WHERE` desapareciera, aquí
                // saldrían dos filas y `items` dejaría de significar «lo que está mal»
                // para pasar a ser «todo el catálogo», que es lo que prometía el
                // contrato viejo y lo que haría inútil el campo `balanced`.
                //
                // Comprobado ejecutándolo: un producto cuadrado devuelve **cero filas**.
                divergences.size shouldBe 1
                divergences.single().productCode shouldBe "DIV1"
            }
        }

        it("con el inventario cuadrado el informe viene vacío, que es el estado sano") {
            withDatabase { pool ->
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute("INSERT INTO products (product_code, name, unit_measure_id) VALUES ('OK1','CUADRA',1)")
                        val unassigned = "SELECT id FROM locations WHERE kind='UNASSIGNED'"
                        st.execute(
                            "SELECT registrar_movimiento(1,1,'INITIAL',1000,NULL,($unassigned)," +
                                "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                        )
                    }
                }

                val service = InventoryService(pool, dev.axzy.stock.backend.infrastructure.outbound.persistence.audit.AuditWriter(pool))

                // Cero filas ⇒ `balanced = true`. Es la respuesta NORMAL, y por eso
                // el reporte publica un booleano explícito en vez de obligar al
                // cliente a contar el arreglo.
                service.divergences().size shouldBe 0
            }
        }
    }
})
