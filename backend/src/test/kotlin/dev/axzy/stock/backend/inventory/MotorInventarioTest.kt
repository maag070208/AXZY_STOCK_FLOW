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
 * **ESTADO: INCOMPLETO. El segundo test FALLA y se deja así a propósito.**
 *
 * El motor tiene dos bugs ya corregidos —una colisión de nombres con `kind` y una
 * ambigüedad con `quantity` dentro del `ON CONFLICT`— pero **la secuencia de
 * ubicar sigue fallando** y no se ha aislado la causa con el rigor necesario.
 *
 * Se deja el test **en rojo** en lugar de comentado o borrado: un test que falla y
 * describe el problema vale más que una nota que dice «hay algo mal». Quien lo
 * retome empieza leyendo su salida.
 *
 * **Nota de método:** todas las operaciones van por la MISMA conexión. Mis pruebas
 * manuales con `docker exec` fallaban en parte porque cada invocación abría una
 * conexión distinta y los identificadores que pasaba no correspondían a esa base.
 * Ése fue un error de método, no del motor, y por eso el test lo hace bien.
 *
 * **Fallo observable:** al ubicar 300 unidades desde `SIN-UBICAR`, que tiene 1000,
 * el `ON CONFLICT` **no actualiza la fila existente e intenta insertar una nueva
 * con -300**, violando `stock_non_negative`. Con un delta POSITIVO sí actualiza,
 * así que la diferencia entre ambos casos es lo que hay que entender.
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

                    // ÉSTE es el caso que falla hoy. Ver el comentario de la clase.
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
