package dev.axzy.stock.backend.inventory

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.sql.Connection
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DEL MOTOR DE INVENTARIO (migración V5)
 *
 * **ESTADO: EN VERDE. Los dos tests pasan.**
 *
 * **Nota de método, y es la lección que me costó más:** todas las operaciones van
 * por la MISMA conexión y sobre una base recreada en cada caso. Mis pruebas
 * manuales con `docker exec` abrían una conexión distinta en cada invocación y
 * pasaban identificadores que no correspondían a esa base; diagnostiqué varias
 * iteraciones a partir de salidas que mi propio `grep` recortaba.
 *
 * **La causa que costó encontrar, y es una regla de PostgreSQL:** la primera
 * versión del motor usaba `INSERT ... ON CONFLICT DO UPDATE` proponiendo la fila
 * con el delta. **No se puede**, porque PostgreSQL valida los `CHECK` de la tabla
 * sobre la fila **propuesta** ANTES de resolver el conflicto. Así que proponer un
 * valor negativo falla siempre, aunque el conflicto lo fuera a convertir en
 * positivo (1000 + (−300) = 700 nunca se llegaba a calcular).
 *
 * El síntoma era desconcertante: con `+1000` funcionaba y con `−300` no, y el
 * error señalaba `stock_non_negative`. Parecía un problema del saldo y era de la
 * mecánica del `ON CONFLICT`. La solución es intentar el `UPDATE` primero y hacer
 * el `INSERT` solo si no afectó a ninguna fila.
 *
 * El segundo test es el que más importa de este archivo: **ubicar NO cambia el
 * total**. Si algún día cambia, el modelo se habrá roto y este test lo dice.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class MotorInventarioTest : DescribeSpec({

    val nombreBase = "stock_motor_test"

    fun contrasenaLocal(): String {
        val archivo: File? = listOf(File("infra/.env"), File("../infra/.env"))
            .firstOrNull { it.exists() }
        return archivo?.readLines()
            ?.firstOrNull { it.startsWith("POSTGRES_PASSWORD=") }
            ?.substringAfter("=")?.trim() ?: ""
    }

    val config = DatabaseConfig(
        jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/$nombreBase",
        username = System.getenv("STOCK_TEST_DB_USER") ?: "stock_app",
        password = System.getenv("STOCK_TEST_DB_PASSWORD") ?: contrasenaLocal(),
        // 2 y no 1: Flyway necesita una conexion mientras el test mantiene la
        // suya. Con el pool a 1, la peticion de Flyway se queda esperando y el
        // test falla por AGOTAMIENTO DEL POOL, no por el motor.
        maxPoolSize = 2,
        runMigrations = false,
    )

    val configAdmin = config.copy(jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/postgres")

    fun cerrar(pool: DataSource?) {
        runCatching { (pool as? AutoCloseable)?.close() }
    }

    /** Base disponible. Si no, los tests se saltan y lo dicen. */
    val hayBase: Boolean = run {
        val pool = runCatching { buildDataSource(configAdmin) }.getOrNull() ?: return@run false
        try {
            pool.connection.use { c ->
                c.createStatement().use { st -> st.executeQuery("SELECT 1").use { it.next() } }
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            cerrar(pool)
        }
    }

    if (!hayBase) {
        println("MotorInventarioTest SALTADO: no hay PostgreSQL. cd infra && docker compose up -d")
    }

    /**
     * Recrea la base y aplica las migraciones.
     *
     * Se recrea en cada test para que sean **repetibles**: un test que depende de
     * lo que dejó el anterior pasa o falla según el orden, y eso es peor que no
     * tenerlo.
     */
    fun prepararBase(): DataSource? {
        val admin = runCatching { buildDataSource(configAdmin) }.getOrNull() ?: return null

        try {
            admin.connection.use { c ->
                c.autoCommit = true
                c.createStatement().use { st ->
                    st.execute("DROP DATABASE IF EXISTS $nombreBase WITH (FORCE)")
                    st.execute("CREATE DATABASE $nombreBase")
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            cerrar(admin)
        }

        val pool = buildDataSource(config)
        return try {
            migrate(pool)
            pool
        } catch (e: Exception) {
            cerrar(pool)
            throw e
        }
    }

    fun <T> conBase(bloque: (DataSource) -> T): T? {
        if (!hayBase) return null
        val pool = prepararBase() ?: return null
        return try {
            bloque(pool)
        } finally {
            cerrar(pool)
        }
    }

    /** Ejecuta SQL y devuelve la primera columna de la primera fila. */
    fun Connection.escalar(sql: String): String? =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    /** Prepara un producto y un estante. Devuelve el id del estante. */
    fun Connection.datosDePrueba(): String {
        createStatement().use { st ->
            st.execute("INSERT INTO products (product_code, name, unit_measure_id) VALUES ('X1','P1',1)")
            st.execute(
                "INSERT INTO locations (warehouse_id, hall, code, position, levels, kind) " +
                    "VALUES (1,'05','A','A',1,'SHELF')",
            )
        }
        return escalar("SELECT id FROM locations WHERE hall='05'")!!
    }

    describe("el motor de inventario mantiene las proyecciones") {

        it("la carga inicial crea el saldo y el total") {
            conBase { pool ->
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.datosDePrueba()

                    val unassigned = c.escalar("SELECT id FROM locations WHERE kind='UNASSIGNED'")

                    c.escalar(
                        "SELECT registrar_movimiento(1,1,'INITIAL',1000,NULL,$unassigned," +
                            "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                    ) shouldBe "1"

                    c.escalar("SELECT quantity::text FROM product_stock WHERE product_id=1") shouldBe "1000.000"
                }
            }
        }

        it("ubicar NO cambia el total: es un traslado, no una salida") {
            conBase { pool ->
                pool.connection.use { c ->
                    c.autoCommit = true
                    val estante = c.datosDePrueba()

                    val unassigned = c.escalar("SELECT id FROM locations WHERE kind='UNASSIGNED'")

                    c.escalar(
                        "SELECT registrar_movimiento(1,1,'INITIAL',1000,NULL,$unassigned," +
                            "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                    )

                    // El caso que más importa: ubicar es un TRASLADO, así que el
                    // total no debe moverse.
                    c.escalar(
                        "SELECT registrar_movimiento(1,1,'LOCATE',300,$unassigned,$estante," +
                            "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                    )

                    c.escalar("SELECT quantity::text FROM product_stock WHERE product_id=1") shouldBe "1000.000"
                }
            }
        }
    }
})
