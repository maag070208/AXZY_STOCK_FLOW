package dev.axzy.stock.backend.reports

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REPORTES (§9) CONTRA PostgreSQL REAL
 *
 * **Lo que estas pruebas cubren y ninguna otra puede:** que el SQL sea correcto.
 * Un `CASE` mal escrito, un `GROUP BY` que agrupa por la columna equivocada o un
 * `SUM` que suma la lista de tipos en vez de las unidades **compilan igual** y
 * devuelven una cifra plausible. La única forma de saberlo es ejecutarlo contra
 * el motor.
 *
 * Los datos se fabrican con `registrar_movimiento`, la MISMA función que usa la
 * aplicación, y no con `INSERT` a mano: así el kardex y las proyecciones quedan
 * coherentes —que es lo que el reporte lee— y la prueba no pasa por un estado que
 * en producción no puede existir.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ReportServiceTest : DescribeSpec({

    val nameBase = "stock_reports_test"

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
        println("ReportServiceTest SALTADO: no hay PostgreSQL. cd infra && docker compose up -d")
    }

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

    /** Crea un producto y devuelve su id. */
    fun DataSource.createProduct(code: String, name: String = "Producto $code"): Long =
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO products (product_code, name, unit_measure_id) " +
                        "VALUES ('$code','$name',1)",
                )
            }
            c.createStatement().use { st ->
                st.executeQuery("SELECT id FROM products WHERE product_code = '$code'").use { rs ->
                    rs.next(); rs.getLong(1)
                }
            }
        }

    /** El identificador de la ubicación `SIN-UBICAR`, que la migración siembra. */
    fun DataSource.unassignedLocation(): Long = connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT id FROM locations WHERE kind='UNASSIGNED'").use { rs ->
                rs.next(); rs.getLong(1)
            }
        }
    }

    /**
     * Registra un movimiento por la vía REAL: kardex y proyecciones coherentes.
     *
     * Se llama a `registrar_movimiento`, no se hace `INSERT` a mano, porque es la
     * función que mantiene el kardex y el saldo en la misma transacción. Un
     * `INSERT` directo dejaría el reporte leyendo un estado que en producción no
     * puede existir, y la prueba pasaría por un dato inventado.
     *
     * @param from ubicación de origen. **Obligatoria en `LOCATE` y en los
     *   traslados internos**: la base lo exige con
     *   `kardex_locate_requires_locations` y lo descubrí porque el test falló.
     */
    fun DataSource.move(
        productId: Long,
        type: String,
        units: String,
        toLocation: Long?,
        from: Long? = null,
    ) {
        val fromSql = from?.toString() ?: "NULL"
        val toSql = toLocation?.toString() ?: "NULL"
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute(
                    "SELECT registrar_movimiento($productId, 1, '$type'::movement_type, " +
                        "$units::numeric, $fromSql, $toSql, " +
                        "NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
                )
            }
        }
    }

    /** La fecha de HOY según la BASE, no según la máquina del test. */
    fun DataSource.today(): LocalDate = connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT CURRENT_DATE").use { rs -> rs.next(); rs.getDate(1).toLocalDate() }
        }
    }

    /** Un estante nuevo, para tener dos ubicaciones donde mover. */
    fun DataSource.newShelf(hall: String): Long {
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO locations (warehouse_id, hall, code, \"position\", levels, kind) " +
                        "VALUES (1,'$hall','A','A',1,'SHELF')",
                )
            }
        }
        return connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT id FROM locations WHERE hall='$hall'").use { rs ->
                    rs.next(); rs.getLong(1)
                }
            }
        }
    }

    /**
     * Base LIMPIA antes de cada prueba. Se recrea entera.
     *
     * **Se probó a borrar filas y no se puede.** `kardex` es append-only y sus
     * disparadores PROHÍBEN el `DELETE` a propósito —es el requisito de auditoría
     * del proyecto—, y además tiene una clave foránea a `products`: borrar un
     * producto para «limpiar» falla con
     * `update or delete on table "products" violates foreign key constraint`.
     *
     * **Desactivar los disparadores para poder limpiar sería lo peor que se puede
     * hacer aquí**: el test dejaría de comprobar que el ledger no se puede
     * editar, que es justo lo que el disparador garantiza. Se recrea la base y se
     * pagan los milisegundos de Flyway.
     */
    beforeTest {
        if (thereIsBase) {
            val pool = prepareDatabase()
            close(pool)
        }
    }

    describe("existencias por producto y ubicación") {

        it("devuelve los totales calculados sobre el filtro, no sobre la página") {
            withDatabase { pool ->
                val a = pool.createProduct("AAA111")
                val b = pool.createProduct("BBB222")
                val ubicacion = pool.unassignedLocation()
                pool.move(a, "INITIAL", "1000", ubicacion)
                pool.move(b, "INITIAL", "500", ubicacion)

                val service = ReportService(pool)

                // La página trae UNA fila, pero el total cuenta las DOS.
                val items = service.inventory(null, null, onlyWithStock = true, offset = 0, limit = 1)
                val total = service.countInventory(null, null, onlyWithStock = true)
                val totales = service.inventoryTotals(null, null, onlyWithStock = true)

                items.size shouldBe 1
                // **La prueba del `totalItems`**: si se calculara sobre la página,
                // aquí pondría 1 y el usuario creería que solo hay un producto.
                total shouldBe 2
                totales.distinctProducts shouldBe 2
                totales.totalUnits shouldBe "1500.000"
            }
        }

        it("el filtro por bodega cambia los totales, no solo las filas") {
            withDatabase { pool ->
                val a = pool.createProduct("AAA111")
                val ubicacion = pool.unassignedLocation()
                pool.move(a, "INITIAL", "1000", ubicacion)

                val service = ReportService(pool)

                // La bodega sembrada se llama PRINCIPAL.
                val conFiltro = service.inventoryTotals("PRINCIPAL", null, true)
                val sinFiltro = service.inventoryTotals(null, null, true)

                conFiltro.totalUnits shouldBe sinFiltro.totalUnits

                // Y una bodega que no existe da CERO, no el total: un filtro que no
                // filtra es peor que no tenerlo.
                val inexistente = service.inventoryTotals("NO-EXISTE", null, true)
                inexistente.distinctProducts shouldBe 0
                inexistente.totalUnits shouldBe "0.000"
                // Y el cero lleva los TRES decimales del contrato: `SUM` sobre
                // cero filas devolvía `0` a secas y rompía el formato de la
                // columna. Lo encontró este test.
                inexistente.totalUnits.length shouldBe 5
            }
        }

        it("`onlyWithStock = false` incluye las filas a cero; `true` las omite") {
            withDatabase { pool ->
                val a = pool.createProduct("AAA111")
                val ubicacion = pool.unassignedLocation()
                pool.move(a, "INITIAL", "1000", ubicacion)
                // Una SALIDA saca de una ubicación: el origen es lo que importa y
                // el destino va nulo. Pasarlo como destino dejaba el saldo intacto
                // y el test fallaba con un «expected 0 but was 1» que parecía un
                // fallo del filtro. Lo encontró el propio test.
                pool.move(a, "FULFILLMENT_OUT", "1000", toLocation = null, from = ubicacion)

                val service = ReportService(pool)

                service.inventory(null, null, onlyWithStock = true, offset = 0, limit = 10).size shouldBe 0
                service.inventory(null, null, onlyWithStock = false, offset = 0, limit = 10).size shouldBe 1
            }
        }

        it("`distinctProducts` cuenta productos, no filas: uno en dos ubicaciones es UNO") {
            withDatabase { pool ->
                val a = pool.createProduct("AAA111")
                val sinUbicar = pool.unassignedLocation()

                val estante = pool.newShelf("07")

                pool.move(a, "INITIAL", "1000", sinUbicar)
                // `LOCATE` mueve de la ubicación de recepción/sin-ubicar AL estante:
                // por eso exige las dos, y por eso se pasan las dos.
                pool.move(a, "LOCATE", "400", estante, from = sinUbicar)
                // `LOCATE` resta de SIN-UBICAR y suma en el estante: el total sigue
                // siendo 1000 y ahora hay DOS filas para el mismo producto.

                val service = ReportService(pool)
                val totales = service.inventoryTotals(null, null, onlyWithStock = true)
                val filas = service.inventory(null, null, true, 0, 50)

                filas.size shouldBe 2
                totales.distinctProducts shouldBe 1
                totales.totalUnits shouldBe "1000.000"
                // El stock se reparte: 600 sin ubicar y 400 en el estante.
                filas.map { it.quantity } shouldContain "600.000"
                filas.map { it.quantity } shouldContain "400.000"
            }
        }
    }

    describe("resumen de movimientos") {

        it("agrupa por día y suma entradas y salidas en columnas separadas") {
            withDatabase { pool ->
                val a = pool.createProduct("AAA111")
                val ubicacion = pool.unassignedLocation()
                pool.move(a, "INITIAL", "1000", ubicacion)
                pool.move(a, "FULFILLMENT_OUT", "300", ubicacion)

                val service = ReportService(pool)
                val hoy = pool.today()
                val (totales, items) = service.movementsSummary(hoy, hoy, "day")

                items.size shouldBe 1
                items.single().inUnits shouldBe "1000.000"
                items.single().outUnits shouldBe "300.000"
                // La resta la hace el SERVIDOR: el cliente no debería repetirla.
                items.single().netUnits shouldBe "700.000"
                items.single().movements shouldBe 2

                totales.inUnits shouldBe "1000.000"
                totales.outUnits shouldBe "300.000"
                totales.netUnits shouldBe "700.000"
                totales.movements shouldBe 2
            }
        }

        it("`LOCATE` y `MOVE_WITHIN_WAREHOUSE` NO cuentan como salida: no mueven el total") {
            withDatabase { pool ->
                val a = pool.createProduct("AAA111")
                val sinUbicar = pool.unassignedLocation()

                val estante = pool.newShelf("08")

                pool.move(a, "INITIAL", "1000", sinUbicar)
                pool.move(a, "LOCATE", "400", estante, from = sinUbicar)

                val service = ReportService(pool)
                val hoy = pool.today()
                val (totales, _) = service.movementsSummary(hoy, hoy, "day")

                // 1400 movidos en total, pero **cero salidas**: solo cambiaron de
                // sitio. Contarlos como salida haría que «salieron 400» no cuadrara
                // con el kardex, y el usuario desconfiaría del reporte entero.
                totales.outUnits shouldBe "0.000"
                totales.inUnits shouldBe "1000.000"
                totales.netUnits shouldBe "1000.000"
                // El CONTEO sí los incluye: son dos movimientos reales del ledger.
                totales.movements shouldBe 2
            }
        }

        it("agrupa por producto y devuelve el código como clave") {
            withDatabase { pool ->
                val a = pool.createProduct("AAA111")
                val b = pool.createProduct("BBB222")
                val ubicacion = pool.unassignedLocation()
                pool.move(a, "INITIAL", "1000", ubicacion)
                pool.move(b, "INITIAL", "500", ubicacion)

                val service = ReportService(pool)
                val hoy = pool.today()
                val (_, items) = service.movementsSummary(hoy, hoy, "product")

                items.size shouldBe 2
                items.map { it.key } shouldContain "AAA111"
                items.map { it.key } shouldContain "BBB222"
            }
        }

        it("un rango SIN movimientos devuelve listas vacías y totales a cero, no un error") {
            withDatabase { pool ->
                val service = ReportService(pool)
                // Un rango en el pasado remoto: no hay nada.
                val (totales, items) = service.movementsSummary(
                    LocalDate.of(2000, 1, 1),
                    LocalDate.of(2000, 1, 31),
                    "day",
                )

                items.size shouldBe 0
                totales.movements shouldBe 0
                totales.inUnits shouldBe "0.000"
                // Cero con 3 decimales, no «0». El contrato fija el formato y el
                // cliente lo parsea: un «0» suelto rompería el formato de la columna.
                totales.netUnits shouldBe "0.000"
            }
        }

        it("un `groupBy` desconocido lanza 400 con los valores admitidos") {
            withDatabase { pool ->
                val service = ReportService(pool)
                val hoy = pool.today()

                val error = runCatching { service.movementsSummary(hoy, hoy, "inventado") }
                    .exceptionOrNull()

                // **Se comprueba el TIPO y el CÓDIGO, no el texto.** Afirmar sobre
                // el mensaje hacía que el test dependiera de cómo se imprime una
                // excepción, y `toString()` de `BadRequestException` no incluye las
                // `details` —así que la primera versión de esta prueba fallaba por
                // cómo lee el test, no por el código—.
                error.shouldBeInstanceOf<dev.axzy.stock.backend.error.BadRequestException>()
                val bad = error as dev.axzy.stock.backend.error.BadRequestException
                bad.code shouldBe "INVALID_PARAMETER"
                // El contrato exige que `details.allowed` diga los admitidos: sin
                // eso el usuario prueba valores al azar.
                bad.details?.get("allowed")?.contains("day") shouldBe true
            }
        }
    }

    describe("exportación del kardex") {

        it("la tabla lleva las columnas del listado y una fila por movimiento") {
            withDatabase { pool ->
                val a = pool.createProduct("AAA111", "ABRAZADERA DE ARRANQUE")
                val ubicacion = pool.unassignedLocation()
                pool.move(a, "INITIAL", "1000", ubicacion)

                val service = ReportService(pool)
                val tabla = service.kardexExport(null, null, null, null)

                tabla.headers.size shouldBe 14
                tabla.headers shouldContain "Producto"
                tabla.headers shouldContain "Unidades"
                tabla.rows.size shouldBe 1
                tabla.rows.single()[1] shouldBe "AAA111"
                tabla.rows.single()[4] shouldBe "1000.000"
            }
        }

        it("el día `hasta` entra ENTERO en el rango") {
            // El fallo clásico: `moved_at <= 'hasta 00:00'` deja fuera todo el día
            // `hasta`. Solo se nota el día que alguien cuadra el mes y ve que
            // faltan movimientos del último día.
            withDatabase { pool ->
                val a = pool.createProduct("AAA111")
                val ubicacion = pool.unassignedLocation()
                pool.move(a, "INITIAL", "1000", ubicacion)

                val service = ReportService(pool)
                val hoy = pool.today()

                service.kardexExport(null, null, hoy, hoy).rows.size shouldBe 1
                // Y el día anterior no lo incluye.
                service.kardexExport(null, null, hoy.minusDays(1), hoy.minusDays(1)).rows.size shouldBe 0
            }
        }

        it("el CSV y el XLSX generados desde el kardex real abren") {
            withDatabase { pool ->
                val a = pool.createProduct("AAA111")
                val ubicacion = pool.unassignedLocation()
                pool.move(a, "INITIAL", "1000", ubicacion)

                val tabla = ReportService(pool).kardexExport(null, null, null, null)

                tabla.toCsv().toString(Charsets.UTF_8) shouldContain "AAA111"

                // El XLSX se descomprime de verdad: si el ZIP o un XML estuvieran
                // mal, esto revienta y no llega a producción un archivo que Excel
                // rechaza.
                val bytes = tabla.toXlsx("Kardex")
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    var nombres = 0
                    while (zip.nextEntry != null) nombres++
                    nombres shouldBe 6
                }
            }
        }
    }
})
