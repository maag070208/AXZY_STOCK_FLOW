package dev.axzy.stock.backend.transfer

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA INVARIANTE DEL TRASLADO, CONTRA PostgreSQL REAL
 *
 * > **`Σ stock_by_location + Σ stock_in_transit` se conserva en CADA
 * > transición.** (contrato §7)
 *
 * **Por qué esta prueba es la importante de todo el módulo.** No comprueba que
 * las rutas respondan ni que el JSON tenga los campos: comprueba que **la
 * mercancía no se crea ni se destruye** al moverla entre bodegas. Si el despacho
 * restara de las ubicaciones y el tránsito no se insertara —o al revés—, el total
 * del producto bajaría o subiría, y **los reportes mentirían**: un informe de
 * existencias a mitad de un traslado enseñaría una cifra que no existe.
 *
 * La suma se lee de la BASE con una consulta, no del código bajo prueba. Si se
 * leyera del servicio, la prueba diría que el servicio coincide consigo mismo.
 *
 * ── POR QUÉ RECREA LA BASE EN CADA PRUEBA ──────────────────────────────────
 *
 * `kardex` es append-only y sus disparadores PROHÍBEN el `DELETE` a propósito, y
 * tiene clave foránea a `products`: no se puede «limpiar» borrando filas sin
 * desactivar la protección que el proyecto quiere tener. Se recrea la base y se
 * pagan los milisegundos de Flyway — que además prueban el camino de un
 * despliegue nuevo, ahora con la migración V9.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class TransferInvariantTest : DescribeSpec({

    val nameBase = "stock_transfer_test"

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
        println("TransferInvariantTest SALTADO: no hay PostgreSQL. cd infra && docker compose up -d")
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

    /** Las cuatro referencias que necesita una prueba de traslado. */
    data class Scenario(
        val productId: Long,
        val shelfLocationId: Long,
        val transitLocation: Long,
        val destinationWarehouseId: Long,
    )

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
     * EL NÚMERO QUE MANDA: ubicaciones + tránsito NO recibido.
     *
     * Es exactamente la fórmula de `recalcular_total_producto` (V6), escrita
     * aparte a propósito: **si se copiara del código, la prueba no probaría
     * nada** — compararía la función consigo misma.
     */
    fun DataSource.totalUnits(productId: Long): String = connection.use { c ->
        c.prepareStatement(
            """
            SELECT (
                COALESCE((SELECT SUM(quantity) FROM stock_by_location WHERE product_id = ?), 0)
              + COALESCE((SELECT SUM(quantity) FROM stock_in_transit
                          WHERE product_id = ? AND received_at IS NULL), 0)
            )::text
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, productId)
            st.setLong(2, productId)
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    /** Prepara dos bodegas, un producto con stock y una ubicación de origen. */
    fun DataSource.scenario(): Scenario {
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute("INSERT INTO warehouses (code, name, is_default) VALUES ('SUC','Sucursal', false)")
                st.execute("INSERT INTO products (product_code,name,unit_measure_id) VALUES ('TRF1','Traslado',1)")
                st.execute(
                    "INSERT INTO locations (warehouse_id, hall, code, \"position\", levels, kind) " +
                        "VALUES (1,'09','A','A',1,'SHELF')",
                )
                st.execute(
                    "INSERT INTO locations (warehouse_id, hall, code, \"position\", levels, kind) " +
                        "VALUES (2,'01','A','A',1,'RECEIVING')",
                )
            }
        }
        val ids = connection.use { c ->
            c.createStatement().use { st ->
                fun one(sql: String): Long =
                    st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }

                val productId = one("SELECT id FROM products WHERE product_code='TRF1'")
                val shelf = one("SELECT id FROM locations WHERE hall='09'")
                val transit = one("SELECT id FROM locations WHERE kind='UNASSIGNED'")
                Scenario(productId, shelf, transit, one("SELECT id FROM warehouses WHERE code='SUC'"))
            }
        }

        // Stock inicial: 1000 unidades en el estante de la bodega 1.
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute(
                    "SELECT registrar_movimiento(${ids.productId},1,'INITIAL',1000,NULL," +
                        "${ids.transitLocation},NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                )
                st.execute(
                    "SELECT registrar_movimiento(${ids.productId},1,'LOCATE',1000," +
                        "${ids.transitLocation},${ids.shelfLocationId},NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                )
            }
        }
        return ids
    }

    fun DataSource.dispatchTransfer(scenario: Scenario): Pair<Long, String> {
        val service = TransferService(this)
        val creado = service.create(
            TransferCreateRequest(
                fromWarehouseId = 1,
                toWarehouseId = scenario.destinationWarehouseId,
                note = "prueba de invariante",
                lines = listOf(
                    TransferLineRequest(
                        productCode = "TRF1",
                        quantity = "400.000",
                        fromLocationId = scenario.shelfLocationId,
                    ),
                ),
            ),
            userId = null,
        )
        val antes = totalUnits(scenario.productId)
        val despachado = service.dispatch(creado.transfer.id, userId = null)
        return despachado.transfer.id to antes
    }

    describe("la invariante de conservación") {

        it("crear un borrador NO mueve nada: es una intención") {
            withDatabase { pool ->
                val scenario = pool.scenario()
                val antes = pool.totalUnits(scenario.productId)

                TransferService(pool).create(
                    TransferCreateRequest(
                        fromWarehouseId = 1,
                        toWarehouseId = scenario.destinationWarehouseId,
                        lines = listOf(
                            TransferLineRequest(
                                productCode = "TRF1",
                                quantity = "400.000",
                                fromLocationId = scenario.shelfLocationId,
                            ),
                        ),
                    ),
                    userId = null,
                )

                // El borrador no toca el inventario: la mercancía sale al
                // DESPACHAR, que es un acto con responsable.
                pool.totalUnits(scenario.productId) shouldBe antes
            }
        }

        it("cada transición deja su registro en la bitácora, con el estado anterior y el nuevo") {
            withDatabase { pool ->
                val scenario = pool.scenario()
                val (id, _) = pool.dispatchTransfer(scenario)
                TransferService(pool).receive(id, userId = null)

                // Se leen del mismo modo que lo vería la bitácora: acción y estados como texto.
                val registros = pool.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery(
                            "SELECT action, previous_state::text, new_state::text FROM audit_log " +
                                "WHERE entity_type = 'transfer' AND entity_id = '$id' ORDER BY id",
                        ).use { rs ->
                            buildList {
                                while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getString(3)))
                            }
                        }
                    }
                }

                registros.map { it.first } shouldBe listOf("transfer.create", "transfer.dispatch", "transfer.receive")
                // El despacho muestra DRAFT → DISPATCHED: el registro dice de dónde venía,
                // no solo a dónde fue.
                registros[1].second shouldBe """{"status": "DRAFT"}"""
                registros[1].third shouldBe """{"status": "DISPATCHED"}"""
            }
        }

        it("DESPACHAR conserva el total: sale de la ubicación y entra en tránsito") {
            withDatabase { pool ->
                val scenario = pool.scenario()
                val antes = pool.totalUnits(scenario.productId)
                antes shouldBe "1000.000"

                val (_, _) = pool.dispatchTransfer(scenario)

                // **La prueba central.** La mercancía ya no está en el estante
                // —salió— pero sigue contando porque está en tránsito. Si el
                // despacho restara sin insertar el tránsito, aquí saldría 600.
                pool.totalUnits(scenario.productId) shouldBe antes

                // Y se comprueba POR DÓNDE está, no solo cuánto suma: 600 en el
                // estante y 400 en tránsito. Un total correcto con la mercancía en
                // el sitio equivocado seguiría siendo un fallo.
                pool.connection.use { c ->
                    c.createStatement().use { st ->
                        val enEstante = st.executeQuery(
                            "SELECT quantity::text FROM stock_by_location " +
                                "WHERE product_id=${scenario.productId} AND location_id=${scenario.shelfLocationId}",
                        ).use { rs -> rs.next(); rs.getString(1) }
                        enEstante shouldBe "600.000"

                        val enTransito = st.executeQuery(
                            "SELECT COALESCE(SUM(quantity),0)::text FROM stock_in_transit " +
                                "WHERE product_id=${scenario.productId} AND received_at IS NULL",
                        ).use { rs -> rs.next(); rs.getString(1) }
                        enTransito shouldBe "400.000"
                    }
                }
            }
        }

        it("RECIBIR conserva el total: llega a destino y el tránsito se cierra") {
            withDatabase { pool ->
                val scenario = pool.scenario()
                val antes = pool.totalUnits(scenario.productId)

                val service = TransferService(pool)
                val creado = service.create(
                    TransferCreateRequest(
                        fromWarehouseId = 1,
                        toWarehouseId = scenario.destinationWarehouseId,
                        lines = listOf(
                            TransferLineRequest(
                                productCode = "TRF1",
                                quantity = "400.000",
                                fromLocationId = scenario.shelfLocationId,
                            ),
                        ),
                    ),
                    userId = null,
                )
                service.dispatch(creado.transfer.id, userId = null)
                service.receive(creado.transfer.id, userId = null)

                // **Se conserva en la segunda transición también.** La mercancía
                // está ahora en la ubicación de recepción de la bodega 2.
                pool.totalUnits(scenario.productId) shouldBe antes

                pool.connection.use { c ->
                    c.createStatement().use { st ->
                        val enDestino = st.executeQuery(
                            "SELECT COALESCE(SUM(s.quantity),0)::text FROM stock_by_location s " +
                                "JOIN locations l ON l.id = s.location_id " +
                                "WHERE s.product_id=${scenario.productId} AND l.warehouse_id=${scenario.destinationWarehouseId}",
                        ).use { rs -> rs.next(); rs.getString(1) }
                        enDestino shouldBe "400.000"

                        // El tránsito queda CERRADO: la fila sigue como histórico,
                        // y por eso `recalcular_total_producto` filtra por
                        // `received_at IS NULL`. Sin ese filtro, la misma mercancía
                        // contaría dos veces (está en la ubicación Y en la fila).
                        val abiertas = st.executeQuery(
                            "SELECT count(*)::text FROM stock_in_transit " +
                                "WHERE product_id=${scenario.productId} AND received_at IS NULL",
                        ).use { rs -> rs.next(); rs.getString(1) }
                        abiertas shouldBe "0"
                    }
                }
            }
        }

        it("CANCELAR un despachado devuelve la mercancía y conserva el total") {
            withDatabase { pool ->
                val scenario = pool.scenario()
                val antes = pool.totalUnits(scenario.productId)

                val service = TransferService(pool)
                val creado = service.create(
                    TransferCreateRequest(
                        fromWarehouseId = 1,
                        toWarehouseId = scenario.destinationWarehouseId,
                        lines = listOf(
                            TransferLineRequest(
                                productCode = "TRF1",
                                quantity = "400.000",
                                fromLocationId = scenario.shelfLocationId,
                            ),
                        ),
                    ),
                    userId = null,
                )
                service.dispatch(creado.transfer.id, userId = null)
                service.cancel(creado.transfer.id, userId = null)

                // La mercancía vuelve al estante: **no se queda en un limbo**. Si
                // la cancelación solo cambiara el estado, las 400 unidades
                // seguirían "en tránsito" para siempre y el informe de tránsito
                // tendría una deuda que ya nadie va a recibir.
                pool.totalUnits(scenario.productId) shouldBe antes
                pool.connection.use { c ->
                    c.createStatement().use { st ->
                        val enEstante = st.executeQuery(
                            "SELECT quantity::text FROM stock_by_location " +
                                "WHERE product_id=${scenario.productId} AND location_id=${scenario.shelfLocationId}",
                        ).use { rs -> rs.next(); rs.getString(1) }
                        enEstante shouldBe "1000.000"
                    }
                }
            }
        }

        it("la invariante aguanta el ciclo COMPLETO: 1000 → despachar → recibir → 1000") {
            withDatabase { pool ->
                val scenario = pool.scenario()
                val service = TransferService(pool)

                val inicial = pool.totalUnits(scenario.productId)
                val creado = service.create(
                    TransferCreateRequest(
                        fromWarehouseId = 1,
                        toWarehouseId = scenario.destinationWarehouseId,
                        lines = listOf(
                            TransferLineRequest(
                                productCode = "TRF1",
                                quantity = "400.000",
                                fromLocationId = scenario.shelfLocationId,
                            ),
                        ),
                    ),
                    userId = null,
                )

                val pasos = mutableListOf(inicial)
                service.dispatch(creado.transfer.id, userId = null)
                pasos += pool.totalUnits(scenario.productId)
                service.receive(creado.transfer.id, userId = null)
                pasos += pool.totalUnits(scenario.productId)

                // **Los tres momentos dan el mismo número.** Se afirma sobre la
                // lista entera y no sobre cada paso por separado: así el mensaje
                // del fallo enseña la secuencia completa y se ve EN QUÉ transición
                // se rompió, que es lo que hace falta para arreglarlo.
                pasos shouldBe listOf("1000.000", "1000.000", "1000.000")
            }
        }
    }
})
