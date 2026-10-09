package dev.axzy.stock.backend.order

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA REGLA DEL DESCUENTO DEL SURTIDO, CONTRA PostgreSQL REAL
 *
 * > **`supplied_quantity` es SIEMPRE lo ya descontado del inventario.**
 * > Empacar descuenta lo empacado; confirmar descuenta **lo que FALTA**.
 *
 * ── POR QUÉ ESTA PRUEBA ES LA QUE IMPORTA ─────────────────────────────────
 *
 * Si `confirm` descontara `quantity` entera en vez de `quantity - supplied`,
 * **no fallaría nada**: la ruta responde `200`, el JSON es correcto, las 40
 * pruebas de ruta pasan. Lo que pasa es que el inventario queda **7 unidades por
 * debajo de la realidad** en una línea de 10 con 7 empacadas — y ese descuadre
 * **solo se descubre al cuadrar el almacén**, donde ya no se distingue de una
 * merma real.
 *
 * Por eso la prueba mira **el stock de verdad**, leído de la base, y no el
 * `suppliedQuantity` que devuelve el servicio: comprobar el servicio contra sí
 * mismo no demostraría nada.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class OrderStockDeductionTest : DescribeSpec({

    val nameBase = "stock_order_test"

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
        println("OrderStockDeductionTest SALTADO: no hay PostgreSQL. cd infra && docker compose up -d")
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

    /**
     * Stock REAL del producto, sumando ubicaciones y tránsito abierto.
     *
     * Se lee con SQL propio y **no del servicio**: si se preguntara al código bajo
     * prueba, la prueba diría que el código coincide consigo mismo.
     */
    fun DataSource.stockOf(productCode: String): String = connection.use { c ->
        c.prepareStatement(
            """
            SELECT (
                COALESCE((SELECT SUM(s.quantity) FROM stock_by_location s
                          JOIN products p ON p.id = s.product_id
                          WHERE p.product_code = ?), 0)
              + COALESCE((SELECT SUM(t.quantity) FROM stock_in_transit t
                          JOIN products p ON p.id = t.product_id
                          WHERE p.product_code = ? AND t.received_at IS NULL), 0)
            )::text
            """.trimIndent(),
        ).use { st ->
            st.setString(1, productCode)
            st.setString(2, productCode)
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    data class Fixture(val productCode: String, val locationId: Long)

    /** Un producto con 100 unidades disponibles en un estante. */
    fun DataSource.fixture(code: String = "ORD1"): Fixture {
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute("INSERT INTO products (product_code,name,unit_measure_id) VALUES ('$code','Surtible',1)")
                st.execute(
                    "INSERT INTO locations (warehouse_id, hall, code, \"position\", levels, kind) " +
                        "VALUES (1,'11','A','A',1,'SHELF')",
                )
            }
        }
        val ids = connection.use { c ->
            c.createStatement().use { st ->
                fun one(sql: String): Long = st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
                val productId = one("SELECT id FROM products WHERE product_code='$code'")
                val shelf = one("SELECT id FROM locations WHERE hall='11'")
                val transit = one("SELECT id FROM locations WHERE kind='UNASSIGNED'")
                Triple(productId, shelf, transit)
            }
        }
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute("SELECT registrar_movimiento(${ids.first},1,'INITIAL',100,NULL,${ids.third},NULL,NULL,NULL,NULL,NULL,NULL,NULL)")
                st.execute("SELECT registrar_movimiento(${ids.first},1,'LOCATE',100,${ids.third},${ids.second},NULL,NULL,NULL,NULL,NULL,NULL,NULL)")
            }
        }
        return Fixture(code, ids.second)
    }

    fun DataSource.createOrder(fixture: Fixture, quantity: String = "10.000"): Long {
        val detalle = OrderService(this).create(
            OrderCreateRequest(
                lines = listOf(OrderLineRequest(productCode = fixture.productCode, quantity = quantity)),
            ),
            userId = null,
        )
        return detalle.id
    }

    describe("la regla del descuento") {

        it("empacar 7 de 10 descuenta EXACTAMENTE 7 del inventario") {
            withDatabase { pool ->
                val fixture = pool.fixture()
                val orderId = pool.createOrder(fixture)
                val lineId = OrderService(pool).byId(orderId)!!.lines.single().id

                pool.stockOf(fixture.productCode) shouldBe "100.000"

                OrderService(pool).packageLine(
                    orderId,
                    lineId,
                    PackageLineRequest(quantity = "7.000", locationId = fixture.locationId),
                    userId = null,
                )

                // La salida es de 7: ni de más ni de menos.
                pool.stockOf(fixture.productCode) shouldBe "93.000"

                val linea = OrderService(pool).byId(orderId)!!.lines.single()
                linea.suppliedQuantity shouldBe "7.000"
                // Y el pendiente, que es lo que confirmará, es 3.
                linea.quantity shouldBe "10.000"
            }
        }

        it("CONFIRMAR descuenta lo que FALTA (3), no la cantidad entera (10)") {
            withDatabase { pool ->
                val fixture = pool.fixture()
                val orderId = pool.createOrder(fixture)
                val service = OrderService(pool)
                val lineId = service.byId(orderId)!!.lines.single().id

                service.packageLine(
                    orderId,
                    lineId,
                    PackageLineRequest(quantity = "7.000", locationId = fixture.locationId),
                    userId = null,
                )
                pool.stockOf(fixture.productCode) shouldBe "93.000"

                service.confirm(orderId, userId = null)

                // **LA PRUEBA CENTRAL.** Si `confirm` descontara 10, aquí saldría
                // 83.000 y el inventario habría bajado 17 unidades por una línea
                // de 10. El descuadre se descubriría en un conteo físico y se
                // confundiría con una merma.
                pool.stockOf(fixture.productCode) shouldBe "90.000"

                // Total descontado por la orden: exactamente 10, que es su cantidad.
                val linea = service.byId(orderId)!!.lines.single()
                linea.suppliedQuantity shouldBe "10.000"
                linea.supplied shouldBe true
            }
        }

        it("confirmar SIN empacar antes descuenta la cantidad completa, una sola vez") {
            withDatabase { pool ->
                val fixture = pool.fixture()
                val orderId = pool.createOrder(fixture)
                val service = OrderService(pool)

                pool.stockOf(fixture.productCode) shouldBe "100.000"
                service.confirm(orderId, userId = null)

                // Sin nada empacado, el pendiente es 10 y se descuenta una vez.
                pool.stockOf(fixture.productCode) shouldBe "90.000"
            }
        }

        it("confirmar dos veces se RECHAZA: descontaría el stock dos veces") {
            withDatabase { pool ->
                val fixture = pool.fixture()
                val orderId = pool.createOrder(fixture)
                val service = OrderService(pool)

                service.confirm(orderId, userId = null)
                pool.stockOf(fixture.productCode) shouldBe "90.000"

                val error = runCatching { service.confirm(orderId, userId = null) }.exceptionOrNull()

                // **El conflicto, no un 500.** El código es el del contrato §6.
                error.shouldBeInstanceOf<dev.axzy.stock.backend.error.StockException>()
                (error as dev.axzy.stock.backend.error.StockException).code shouldBe
                    "ORDER_ALREADY_CONFIRMED"
                // Y lo que más importa: el stock NO se movió en el segundo intento.
                pool.stockOf(fixture.productCode) shouldBe "90.000"
            }
        }

        it("empacar en dos tandas suma lo descontado y no descuenta de más") {
            withDatabase { pool ->
                val fixture = pool.fixture()
                val orderId = pool.createOrder(fixture)
                val service = OrderService(pool)
                val lineId = service.byId(orderId)!!.lines.single().id

                service.packageLine(
                    orderId, lineId,
                    PackageLineRequest(quantity = "4.000", locationId = fixture.locationId), null,
                )
                pool.stockOf(fixture.productCode) shouldBe "96.000"

                service.packageLine(
                    orderId, lineId,
                    PackageLineRequest(quantity = "6.000", locationId = fixture.locationId), null,
                )
                pool.stockOf(fixture.productCode) shouldBe "90.000"

                // Ya está completa: confirmar no debe descontar NADA más.
                service.confirm(orderId, userId = null)
                pool.stockOf(fixture.productCode) shouldBe "90.000"
            }
        }


        it("empacar SIN indicar ubicación usa la de la línea, que se fija al CREAR") {
            withDatabase { pool ->
                val fixture = pool.fixture()
                val orderId = pool.createOrder(fixture)
                val service = OrderService(pool)

                // ── El defecto que esta prueba existe para impedir ──────────────
                //
                // `resolveLocation` cae al `location_id` de la LÍNEA cuando el
                // empaque no manda ubicación. La creación insertaba ese campo NULO,
                // así que la caída no servía de nada y empacar respondía
                // `422 LOCATION_REQUIRED` **siempre que el cliente no eligiera
                // estante**.
                //
                // **Ninguna prueba lo veía porque TODAS pasaban `locationId`
                // explícito.** Lo encontró el navegador, que empaca sin elegir
                // ubicación. Es el recordatorio de que una prueba que siempre manda
                // el mismo parámetro no prueba el camino en que falta.
                val linea = service.byId(orderId)!!.lines.single()
                linea.locationId shouldNotBe null

                // Y con esa ubicación, empacar sin decir cuál funciona.
                service.packageLine(
                    orderId,
                    linea.id,
                    PackageLineRequest(quantity = "4.000", locationId = null),
                    userId = null,
                )

                pool.stockOf(fixture.productCode) shouldBe "96.000"
                service.byId(orderId)!!.lines.single().suppliedQuantity shouldBe "4.000"
            }
        }

        it("crear una orden de un producto SIN existencias deja la línea sin ubicación") {
            withDatabase { pool ->
                // Se crea el producto sin darle stock: no hay ninguna ubicación que
                // pueda servir de origen, así que la línea queda sin ella y el
                // empaque pedirá una. Es correcto: sin stock no se puede empacar de
                // ningún sitio, y decir «elige estante» es más útil que fallar con
                // un descuadre.
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute(
                            "INSERT INTO products (product_code,name,unit_measure_id) " +
                                "VALUES ('SINSTOCK','Sin stock',1)",
                        )
                    }
                }

                val orderId = OrderService(pool).create(
                    OrderCreateRequest(
                        lines = listOf(OrderLineRequest(productCode = "SINSTOCK", quantity = "5.000")),
                    ),
                    userId = null,
                ).id

                val linea = OrderService(pool).byId(orderId)!!.lines.single()
                linea.locationId shouldBe null

                // Y empacar sin ubicación se rechaza con el código del contrato, no
                // con un descuadre silencioso.
                val error = runCatching {
                    OrderService(pool).packageLine(
                        orderId,
                        linea.id,
                        PackageLineRequest(quantity = "1.000", locationId = null),
                        userId = null,
                    )
                }.exceptionOrNull()

                (error as dev.axzy.stock.backend.error.StockException).code shouldBe "LOCATION_REQUIRED"
            }
        }

        it("empacar más de lo pendiente se rechaza y NO toca el stock") {
            withDatabase { pool ->
                val fixture = pool.fixture()
                val orderId = pool.createOrder(fixture)
                val service = OrderService(pool)
                val lineId = service.byId(orderId)!!.lines.single().id

                service.packageLine(
                    orderId, lineId,
                    PackageLineRequest(quantity = "7.000", locationId = fixture.locationId), null,
                )

                val error = runCatching {
                    service.packageLine(
                        orderId, lineId,
                        PackageLineRequest(quantity = "5.000", locationId = fixture.locationId), null,
                    )
                }.exceptionOrNull()

                // El mensaje del dominio dice cuánto queda, que es lo que el
                // operador necesita para decidir.
                error.shouldBeInstanceOf<dev.axzy.stock.backend.error.StockException>()
                (error as dev.axzy.stock.backend.error.StockException).code shouldBe
                    "EXCEEDS_PENDING_QUANTITY"
                (error.message ?: "").contains("3000") shouldBe true
                // Y el stock quedó intacto: el rechazo no dejó un movimiento a medias.
                pool.stockOf(fixture.productCode) shouldBe "93.000"
            }
        }

        it("sin existencias suficientes se rechaza con INSUFFICIENT_STOCK y el disponible") {
            withDatabase { pool ->
                val fixture = pool.fixture()
                // Se pide más de lo que hay: 100 disponibles, se ordenan 150.
                val orderId = pool.createOrder(fixture, quantity = "150.000")
                val service = OrderService(pool)
                val lineId = service.byId(orderId)!!.lines.single().id

                val error = runCatching {
                    service.packageLine(
                        orderId, lineId,
                        PackageLineRequest(quantity = "120.000", locationId = fixture.locationId), null,
                    )
                }.exceptionOrNull()

                // El contrato pide `details.available`: sin esa cifra el operador no
                // sabe cuánto puede surtir ni cuánto falta.
                error.shouldBeInstanceOf<dev.axzy.stock.backend.error.StockException>()
                (error as dev.axzy.stock.backend.error.StockException).code shouldBe "INSUFFICIENT_STOCK"
                error.details?.get("available") shouldBe "100.000"
                pool.stockOf(fixture.productCode) shouldBe "100.000"
            }
        }
    }
})
