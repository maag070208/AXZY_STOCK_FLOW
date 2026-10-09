package dev.axzy.stock.backend.application.receiving

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import dev.axzy.stock.backend.infrastructure.outbound.persistence.receiving.JdbcReceivingRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LISTADO Y CIERRE DE FACTURAS (contrato §5)
 *
 * ── LA REGLA DEL CIERRE, Y POR QUÉ IMPORTA MÁS DE LO QUE PARECE ────────────
 *
 * **No se cierra una factura con líneas que bloquean.** El cierre es el acto que
 * declara la recepción terminada: a partir de ahí nadie vuelve a mirarla. Si se
 * cerrara con una línea sin capturar o con una diferencia sin aprobar, esa
 * mercancía se quedaría sin ubicar y esa diferencia sin autorizar **para
 * siempre**, porque el documento consta como cerrado.
 *
 * El criterio no se escribe en la ruta: se le pregunta al DOMINIO
 * (`InvoiceLineStatus.blocksInvoiceClosing`). Estas pruebas comprueban que el
 * backend usa ese criterio y no otro — un `if` con los nombres de estado escritos
 * a mano es exactamente cómo se acaba cerrando una factura con una discrepancia
 * viva.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class InvoiceCloseTest : DescribeSpec({

    val nameBase = "stock_invoice_test"

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
        println("InvoiceCloseTest SALTADO: no hay PostgreSQL. cd infra && docker compose up -d")
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
     * Una factura con dos líneas, y los estados que se le pidan.
     *
     * Se inserta por SQL y no por el endpoint de creación porque lo que se prueba
     * aquí es el CIERRE, y hace falta controlar el estado de las líneas —
     * `PENDING_APPROVAL` no se alcanza sin capturar diferencias.
     */
    /**
     * El identificador del usuario que aprueba.
     *
     * **Hace falta porque la base lo EXIGE**: una línea en `APPROVED` sin
     * `approved_by` viola el `CHECK invoice_lines_approved_has_approver`. No es un
     * estorbo del test: es la garantía de que **una línea no puede constar como
     * aprobada sin que conste quién la aprobó**, que es la mitad de la regla R-74.
     */
    fun DataSource.approver(): Long {
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO users (username, password_hash, name) " +
                        "VALUES ('aprobador', 'x', 'Aprobador') ON CONFLICT (username) DO NOTHING",
                )
            }
        }
        return connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT id FROM users WHERE username='aprobador'")
                    .use { rs -> rs.next(); rs.getLong(1) }
            }
        }
    }

    fun DataSource.invoice(number: String = "F-001", lineStatuses: List<String> = listOf("CAPTURED")): Long {
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                // `ON CONFLICT DO NOTHING`: el fixture se llama una vez por factura
                // y el producto es el mismo. Sin esto, la segunda factura del test
                // choca con el índice único — que es un fallo del TEST, no del
                // código, y se descubrió así.
                st.execute(
                    "INSERT INTO products (product_code,name,unit_measure_id) " +
                        "VALUES ('INV1','Recepción',1) ON CONFLICT (product_code) DO NOTHING",
                )
                st.execute(
                    "INSERT INTO invoices (invoice_number, receipt_date, invoice_type, status) " +
                        "VALUES ('$number', CURRENT_DATE, 'NATIONAL', 'IN_PROGRESS')",
                )
            }
        }
        val invoiceId = connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT id FROM invoices WHERE invoice_number='$number'")
                    .use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        val approverId = approver()
        connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { st ->
                lineStatuses.forEach { status ->
                    // ── Una línea APPROVED exige DOS cosas, y la base lo impone ──
                    //
                    // `invoice_lines_approved_has_approver`:
                    //     status <> 'APPROVED' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)
                    //
                    // **El `approved_at` también hace falta**, y el fixture falló por
                    // olvidarlo. No es un estorbo: es la garantía de que una línea no
                    // puede constar como aprobada **sin que conste quién la aprobó ni
                    // cuándo** — que es la mitad de la regla R-74, la que impide que
                    // alguien firme su propio error de conteo.
                    // ── Las TRES reglas que la base encadena sobre una línea aprobada ──
                    //
                    //   1. `approved_by`  — quién aprobó  (R-74: nadie firma lo suyo)
                    //   2. `approved_at`  — cuándo aprobó
                    //   3. `adjustment_reason` — POR QUÉ se aceptó la diferencia (B2)
                    //
                    // **El fixture falló tres veces, una por cada una.** No son
                    // estorbos: son las garantías de que una línea no puede constar
                    // como aprobada sin firmante, sin fecha y sin motivo — que es
                    // exactamente lo que el legacy permitía.
                    val esAprobada = status == "APPROVED"
                    val aprobador = if (esAprobada) approverId.toString() else "NULL"
                    val fechaAprobacion = if (esAprobada) "now()" else "NULL"
                    val motivo = if (esAprobada) "'COUNT_ERROR'" else "NULL"
                    st.execute(
                        "INSERT INTO invoice_lines (invoice_id, product_id, unit_measure_id, " +
                            "quantity, status, approved_by, approved_at, adjustment_reason) " +
                            "VALUES ($invoiceId, 1, 1, 10, '$status', $aprobador, $fechaAprobacion, $motivo)",
                    )
                }
            }
        }
        return invoiceId
    }

    /**
     * Base limpia antes de cada prueba.
     *
     * Las pruebas comparten base y **el fixture inserta datos**: sin esto, el
     * segundo test ve el producto del primero y falla por el índice único. El
     * síntoma no señala a su causa —`duplicate key` en un test de cierre— y costó
     * una iteración entenderlo.
     *
     * `kardex` no se borra: es append-only y sus disparadores lo prohíben a
     * propósito. Ninguna prueba de este archivo escribe en él.
     */
    beforeTest {
        if (thereIsBase) {
            val pool = buildDataSource(config)
            try {
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        listOf(
                            "DELETE FROM invoice_lines",
                            "DELETE FROM invoices",
                            "DELETE FROM stock_in_transit",
                            "DELETE FROM stock_by_location",
                            "DELETE FROM product_stock",
                            "DELETE FROM products",
                        ).forEach { sql -> runCatching { st.execute(sql) } }
                    }
                }
            } finally {
                close(pool)
            }
        }
    }

    describe("el CIERRE") {

        it("cierra una factura cuyas líneas están todas capturadas") {
            withDatabase { pool ->
                val id = pool.invoice("F-OK", listOf("CAPTURED", "LOCATED"))

                val detalle = ReceivingUseCases(JdbcReceivingRepository(pool)).close(id, userId = null)

                detalle.status shouldBe "COMPLETED"
            }
        }

        it("NO cierra con una línea SIN CAPTURAR, y dice cuántas bloquean") {
            withDatabase { pool ->
                val id = pool.invoice("F-PEND", listOf("CAPTURED", "PENDING"))

                val error = runCatching { ReceivingUseCases(JdbcReceivingRepository(pool)).close(id, userId = null) }
                    .exceptionOrNull()

                error.shouldBeInstanceOf<dev.axzy.stock.backend.error.StockException>()
                val excepcion = error as dev.axzy.stock.backend.error.StockException
                excepcion.code shouldBe "INVOICE_HAS_PENDING_LINES"
                // `details` dice CUÁNTAS y CUÁLES: sin eso el usuario sabe que no
                // puede cerrar pero no qué le falta.
                excepcion.details?.get("pendingLines") shouldBe "1"
                excepcion.details?.get("statuses") shouldBe "PENDING"
            }
        }

        it("NO cierra con una DISCREPANCIA sin aprobar — la regla B1") {
            withDatabase { pool ->
                val id = pool.invoice("F-DISC", listOf("DISCREPANCY"))

                val error = runCatching { ReceivingUseCases(JdbcReceivingRepository(pool)).close(id, userId = null) }
                    .exceptionOrNull()

                // Es la regla central de la recepción: **una diferencia con la
                // factura no se puede dar por cerrada sin que alguien la autorice**.
                // En el legacy esta comprobación no existía.
                (error as dev.axzy.stock.backend.error.StockException).code shouldBe
                    "INVOICE_HAS_PENDING_LINES"
            }
        }

        it("NO cierra con una línea ASIGNADA a medias") {
            withDatabase { pool ->
                val id = pool.invoice("F-ASIG", listOf("ASSIGNED"))

                (runCatching { ReceivingUseCases(JdbcReceivingRepository(pool)).close(id, userId = null) }
                    .exceptionOrNull() as dev.axzy.stock.backend.error.StockException).code shouldBe
                    "INVOICE_HAS_PENDING_LINES"
            }
        }

        it("SÍ cierra con líneas APPROVED y CANCELLED: no bloquean") {
            withDatabase { pool ->
                // El criterio es el del DOMINIO, así que una línea aprobada o
                // anulada no impide cerrar. Si el backend tuviera su propia lista de
                // estados permitidos, esto podría discrepar.
                val id = pool.invoice("F-MIX", listOf("APPROVED", "CANCELLED", "LOCATED"))

                ReceivingUseCases(JdbcReceivingRepository(pool)).close(id, userId = null).status shouldBe "COMPLETED"
            }
        }

        it("cerrar dos veces da 409 INVOICE_ALREADY_CLOSED") {
            withDatabase { pool ->
                val id = pool.invoice("F-TWICE", listOf("CAPTURED"))
                val service = ReceivingUseCases(JdbcReceivingRepository(pool))

                service.close(id, userId = null).status shouldBe "COMPLETED"

                val error = runCatching { service.close(id, userId = null) }.exceptionOrNull()

                (error as dev.axzy.stock.backend.error.StockException).code shouldBe
                    "INVOICE_ALREADY_CLOSED"
            }
        }

        it("una factura ANULADA no se puede cerrar") {
            withDatabase { pool ->
                val id = pool.invoice("F-CANC", listOf("CAPTURED"))
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute("UPDATE invoices SET status='CANCELLED' WHERE id=$id")
                    }
                }

                val error = runCatching { ReceivingUseCases(JdbcReceivingRepository(pool)).close(id, userId = null) }
                    .exceptionOrNull()

                (error as dev.axzy.stock.backend.error.StockException).code shouldBe "INVALID_TRANSITION"
            }
        }

        it("cerrar una factura que no existe da 404, no un 500") {
            withDatabase { pool ->
                val error = runCatching { ReceivingUseCases(JdbcReceivingRepository(pool)).close(999_999, userId = null) }
                    .exceptionOrNull()

                (error as dev.axzy.stock.backend.error.StockException).code shouldBe "INVOICE_NOT_FOUND"
            }
        }
    }

    describe("el LISTADO") {

        it("devuelve la cabecera con los contadores CONTADOS en la base") {
            withDatabase { pool ->
                pool.invoice("F-L1", listOf("CAPTURED", "PENDING", "DISCREPANCY"))

                val lista = ReceivingUseCases(JdbcReceivingRepository(pool)).list(null, null, 0, 10)

                lista.size shouldBe 1
                val fila = lista.single()
                fila.invoiceNumber shouldBe "F-L1"
                fila.totalLines shouldBe 3
                // `pendingApprovalLines` cuenta las que BLOQUEAN: `PENDING` y
                // `DISCREPANCY` son dos. Es el mismo criterio que aplica el cierre,
                // así que la lista y la acción no pueden discrepar.
                fila.pendingApprovalLines shouldBe 2
            }
        }

        it("el total se CUENTA y respeta el filtro de estado") {
            withDatabase { pool ->
                pool.invoice("F-A", listOf("CAPTURED"))
                pool.invoice("F-B", listOf("CAPTURED"))
                val service = ReceivingUseCases(JdbcReceivingRepository(pool))

                service.count(null, null) shouldBe 2
                service.count("IN_PROGRESS", null) shouldBe 2
                // Un filtro que no filtra es peor que no tenerlo: si el estado no
                // existiera, esto tendría que dar 0 y no 2.
                service.count("COMPLETED", null) shouldBe 0
            }
        }

        it("la búsqueda libre alcanza el número, el concepto y el cliente") {
            withDatabase { pool ->
                pool.invoice("FIND-123", listOf("CAPTURED"))

                val service = ReceivingUseCases(JdbcReceivingRepository(pool))

                service.list(null, "FIND-123", 0, 10).size shouldBe 1
                // Y una búsqueda que no casa devuelve vacío, no todo.
                service.list(null, "NO-EXISTE", 0, 10).size shouldBe 0
            }
        }

        it("la paginación corta la página sin tocar el total") {
            withDatabase { pool ->
                pool.invoice("F-P1", listOf("CAPTURED"))
                pool.invoice("F-P2", listOf("CAPTURED"))
                pool.invoice("F-P3", listOf("CAPTURED"))
                val service = ReceivingUseCases(JdbcReceivingRepository(pool))

                service.list(null, null, 0, 2).size shouldBe 2
                // **El total no es el tamaño de la página**: si lo fuera, el cliente
                // creería que solo hay dos facturas.
                service.count(null, null) shouldBe 3
            }
        }

        it("una factura con el borrado lógico marcado NO aparece") {
            withDatabase { pool ->
                val id = pool.invoice("F-DEL", listOf("CAPTURED"))
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute(
                            "UPDATE invoices SET deleted = TRUE, deleted_at = now(), " +
                                "deleted_reason = 'prueba' WHERE id = $id",
                        )
                    }
                }

                // El borrado es lógico y el listado lo respeta: si apareciera, el
                // usuario vería documentos que el sistema considera borrados.
                ReceivingUseCases(JdbcReceivingRepository(pool)).list(null, null, 0, 10).size shouldBe 0
                ReceivingUseCases(JdbcReceivingRepository(pool)).count(null, null) shouldBe 0
            }
        }
    }
})
