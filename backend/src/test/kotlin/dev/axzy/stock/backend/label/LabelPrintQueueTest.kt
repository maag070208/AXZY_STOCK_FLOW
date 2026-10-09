package dev.axzy.stock.backend.label

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA COLA DE IMPRESIÓN, CONTRA PostgreSQL REAL
 *
 * La regla que estas pruebas protegen es la que el contrato señala como el
 * defecto del legacy:
 *
 * > «El legacy la marcaba sin esperar la respuesta, así que si la Zebra estaba
 * > apagada, la etiqueta se perdía para siempre.»
 *
 * Aquí se comprueba que:
 *
 *   · Un trabajo nace `PENDING` **y sigue pendiente** hasta que la impresora
 *     reporta. Ningún endpoint de usuario lo marca impreso.
 *   · La impresora pasa a `PRINTED` mediante su credencial de servicio, y **su
 *     reintento no imprime dos veces** (`409`).
 *   · La reimpresión **crea un trabajo nuevo** con su motivo, en vez de reutilizar
 *     el registro: la trazabilidad exige saber quién y por qué.
 *   · **Sin clave de servicio válida no se puede marcar nada como impreso**, que
 *     es lo que el legacy permitía a cualquiera en la red.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LabelPrintQueueTest : DescribeSpec({

    val nameBase = "stock_label_test"

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
        println("LabelPrintQueueTest SALTADO: no hay PostgreSQL. cd infra && docker compose up -d")
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

    /** Una plantilla lista para usar, con sus campos declarados. */
    fun DataSource.seedTemplate(code: String = "receipt"): Long =
        LabelService(this).createTemplate(
            LabelTemplateCreateRequest(
                code = code,
                name = "Recibo de mercancía",
                zpl = "^XA^FD{{productCode}}^FS^FD{{name}}^FS^XZ",
                size = "4x6",
                fieldSchema = mapOf(
                    "productCode" to LabelFieldDto(type = "string", label = "Código", required = true),
                    "name" to LabelFieldDto(type = "string", label = "Nombre", required = true),
                ),
            ),
        ).id

    describe("el trabajo nace PENDIENTE y NO se marca impreso solo") {

        it("encolar deja los trabajos en PENDING: el sistema no se autocertifica") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()
                val service = LabelService(pool)

                val respuesta = service.print(
                    LabelPrintRequest(
                        templateId = templateId,
                        copies = 2,
                        data = listOf(mapOf("productCode" to "A1", "name" to "UNO")),
                    ),
                    userId = null,
                )

                // `PENDING` y no `PRINTED`: **la etiqueta todavía no salió**. Si se
                // marcara impresa aquí, una Zebra apagada perdería la etiqueta y el
                // sistema creería que se imprimió — el defecto del legacy.
                respuesta.status shouldBe "PENDING"
                respuesta.jobIds.size shouldBe 1

                val trabajo = service.job(respuesta.jobIds.single())!!
                trabajo.status shouldBe "PENDING"
                trabajo.printedAt shouldBe null
                trabajo.copies shouldBe 2
            }
        }

        it("un juego de datos produce un trabajo, y dos producen dos") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()
                val service = LabelService(pool)

                val respuesta = service.print(
                    LabelPrintRequest(
                        templateId = templateId,
                        data = listOf(
                            mapOf("productCode" to "A1", "name" to "UNO"),
                            mapOf("productCode" to "A2", "name" to "DOS"),
                        ),
                    ),
                    userId = null,
                )

                respuesta.jobIds.size shouldBe 2
                service.countJobs("PENDING") shouldBe 2
            }
        }

        it("encolar con datos incompletos se RECHAZA y no crea ningún trabajo") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()
                val service = LabelService(pool)

                val error = runCatching {
                    service.print(
                        LabelPrintRequest(
                            templateId = templateId,
                            data = listOf(mapOf("productCode" to "A1")), // falta `name`
                        ),
                        userId = null,
                    )
                }.exceptionOrNull()

                error.shouldBeInstanceOf<dev.axzy.stock.backend.error.StockException>()
                (error as dev.axzy.stock.backend.error.StockException).code shouldBe "MISSING_LABEL_FIELD"
                // **Cero trabajos.** Se resuelve ANTES de encolar: si no, el trabajo
                // se crearía y habría que descubrir el problema al imprimir, con la
                // etiqueta ya en la cola.
                service.countJobs(null) shouldBe 0
            }
        }
    }

    describe("la impresora es la ÚNICA que marca impreso") {

        it("reportar PRINTED mueve el trabajo y le pone la marca de tiempo") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()
                val service = LabelService(pool)
                val jobId = service.print(
                    LabelPrintRequest(templateId = templateId, data = listOf(datosCompletos())),
                    userId = null,
                ).jobIds.single()

                val resultado = service.updateJobStatus(
                    jobId,
                    PrintJobUpdateRequest(status = "PRINTED"),
                )

                resultado.status shouldBe "PRINTED"
                resultado.jobId shouldBe jobId

                val trabajo = service.job(jobId)!!
                trabajo.status shouldBe "PRINTED"
                trabajo.attempts shouldBe 1
                // La base lo exige con un `CHECK`: `PRINTED` sin `printed_at` sería
                // una etiqueta impresa sin saber cuándo.
                trabajo.printedAt shouldNotBe null
            }
        }

        it("un reintento de la impresora sobre algo YA impreso da 409 y NO imprime dos veces") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()
                val service = LabelService(pool)
                val jobId = service.print(
                    LabelPrintRequest(templateId = templateId, data = listOf(datosCompletos())),
                    userId = null,
                ).jobIds.single()

                service.updateJobStatus(jobId, PrintJobUpdateRequest(status = "PRINTED"))
                val intentosAntes = service.job(jobId)!!.attempts

                val error = runCatching {
                    service.updateJobStatus(jobId, PrintJobUpdateRequest(status = "PRINTED"))
                }.exceptionOrNull()

                error.shouldBeInstanceOf<dev.axzy.stock.backend.error.StockException>()
                (error as dev.axzy.stock.backend.error.StockException).code shouldBe
                    "LABEL_ALREADY_PRINTED"
                // **El contador no subió**: no se gastó una segunda etiqueta.
                service.job(jobId)!!.attempts shouldBe intentosAntes
            }
        }

        it("un fallo de la impresora guarda el motivo y deja el trabajo reintentable") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()
                val service = LabelService(pool)
                val jobId = service.print(
                    LabelPrintRequest(templateId = templateId, data = listOf(datosCompletos())),
                    userId = null,
                ).jobIds.single()

                service.updateJobStatus(
                    jobId,
                    PrintJobUpdateRequest(status = "FAILED", error = "La Zebra no responde"),
                )

                val trabajo = service.job(jobId)!!
                trabajo.status shouldBe "FAILED"
                trabajo.lastError shouldBe "La Zebra no responde"
                // **`printedAt` sigue nulo**: un fallo no es una impresión.
                trabajo.printedAt shouldBe null
                // Y se puede encontrar en la cola, que es lo que hace útil el filtro.
                service.jobs("FAILED", 0, 10).size shouldBe 1
            }
        }

        it("solo se admiten PRINTED y FAILED: no se puede inventar un estado") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()
                val service = LabelService(pool)
                val jobId = service.print(
                    LabelPrintRequest(templateId = templateId, data = listOf(datosCompletos())),
                    userId = null,
                ).jobIds.single()

                val error = runCatching {
                    service.updateJobStatus(jobId, PrintJobUpdateRequest(status = "CASI"))
                }.exceptionOrNull()

                (error as dev.axzy.stock.backend.error.StockException).code shouldBe "INVALID_PARAMETER"
            }
        }
    }

    describe("la reimpresión crea un trabajo NUEVO") {

        it("genera otro trabajo, no reutiliza el registro, y guarda el motivo") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()
                val service = LabelService(pool)
                val originalId = service.print(
                    LabelPrintRequest(templateId = templateId, data = listOf(datosCompletos())),
                    userId = null,
                ).jobIds.single()

                val reimpresion = service.reprint(originalId, reason = "La etiqueta salió arrugada", userId = null)

                // **Un `id` distinto.** Reutilizar el original habría borrado el
                // hecho de que ya se imprimió una vez, que es justo lo que hay que
                // poder auditar cuando alguien pregunta por dos etiquetas.
                (reimpresion.id != originalId) shouldBe true
                reimpresion.status shouldBe "PENDING"
                reimpresion.lastError shouldContain "REIMPRESIÓN"
                reimpresion.lastError shouldContain "arrugada"
                // Y ahora hay DOS trabajos: el original y la reimpresión.
                service.countJobs(null) shouldBe 2
            }
        }

        it("sin motivo se RECHAZA: la trazabilidad exige saber por qué") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()
                val service = LabelService(pool)
                val jobId = service.print(
                    LabelPrintRequest(templateId = templateId, data = listOf(datosCompletos())),
                    userId = null,
                ).jobIds.single()

                val error = runCatching { service.reprint(jobId, reason = "   ", userId = null) }
                    .exceptionOrNull()

                (error as dev.axzy.stock.backend.error.StockException).code shouldBe "REASON_REQUIRED"
                // Y no se creó ningún trabajo nuevo.
                service.countJobs(null) shouldBe 1
            }
        }
    }

    describe("la credencial de servicio") {

        it("sin cabecera, una clave vacía o una inventada NO valen") {
            withDatabase { pool ->
                val service = LabelService(pool)

                // El legacy tenía esta ruta ABIERTA: cualquiera en la red podía
                // marcar trabajos como impresos. Estos tres casos son los que
                // cerraban ese agujero.
                service.isValidServiceKey(null) shouldBe false
                service.isValidServiceKey("") shouldBe false
                service.isValidServiceKey("clave-inventada") shouldBe false
            }
        }

        it("una clave ACTIVA y sin revocar sí vale, y se guarda HASHEADA") {
            withDatabase { pool ->
                val clave = "clave-de-impresora-con-suficiente-entropia-para-el-test"
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(clave.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }

                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute(
                            "INSERT INTO service_api_keys (name, key_hash, scopes) " +
                                "VALUES ('zebra', '$hash', ARRAY['label.print'])",
                        )
                    }
                }

                val service = LabelService(pool)
                service.isValidServiceKey(clave) shouldBe true

                // **En la tabla no está la clave.** Un volcado de la base no
                // permite suplantar a la impresora.
                pool.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT key_hash FROM service_api_keys").use { rs ->
                            rs.next()
                            val guardado = rs.getString(1)
                            (guardado != clave) shouldBe true
                            guardado.length shouldBe 64 // SHA-256 en hexadecimal
                        }
                    }
                }
            }
        }

        it("una clave REVOCADA deja de valer") {
            withDatabase { pool ->
                val clave = "clave-revocada-de-prueba"
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(clave.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }

                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute(
                            "INSERT INTO service_api_keys (name, key_hash, scopes, active, revoked_at) " +
                                "VALUES ('vieja', '$hash', ARRAY['label.print'], true, now())",
                        )
                    }
                }

                // Revocar tiene que surtir efecto: una clave robada que se revoca
                // no puede seguir imprimiendo.
                LabelService(pool).isValidServiceKey(clave) shouldBe false
            }
        }
    }

    describe("la validación del ZPL al guardar") {

        it("un ZPL sin `^XZ` se rechaza AL GUARDAR, no al imprimir") {
            withDatabase { pool ->
                val error = runCatching {
                    LabelService(pool).createTemplate(
                        LabelTemplateCreateRequest(
                            code = "rota",
                            name = "Plantilla sin cierre",
                            zpl = "^XA^FD{{productCode}}^FS",
                        ),
                    )
                }.exceptionOrNull()

                (error as dev.axzy.stock.backend.error.StockException).code shouldBe "INVALID_ZPL"
                // **Se descubre al guardar.** Al imprimir, la impresora no daría
                // error: dejaría la etiqueta en blanco o el trabajo en la cola, y
                // eso se paga en papel.
                LabelService(pool).templates(false).size shouldBe 0
            }
        }

        it("el listado NO lleva `zpl` pero SÍ el `fieldSchema`") {
            withDatabase { pool ->
                pool.seedTemplate()

                val listado = LabelService(pool).templates(false)

                // El listado no puede llevar el ZPL —es grande— pero la interfaz
                // necesita el `fieldSchema` para pintar el formulario.
                listado.size shouldBe 1
                listado.single().fieldSchema.size shouldBe 2
            }
        }

        it("el detalle SÍ lleva el ZPL") {
            withDatabase { pool ->
                val id = pool.seedTemplate()

                val detalle = LabelService(pool).templateById(id)!!

                detalle.zpl shouldContain "^XA"
                detalle.fieldSchema.size shouldBe 2
            }
        }
    }

    describe("la previsualización") {

        it("devuelve el ZPL resuelto y el tamaño en milímetros del servidor") {
            withDatabase { pool ->
                val templateId = pool.seedTemplate()

                val preview = LabelService(pool).preview(
                    LabelPreviewRequest(
                        templateId = templateId,
                        data = mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA"),
                    ),
                )

                preview.zpl shouldContain "ABRAA1B"
                // El tamaño lo calcula el SERVIDOR: si lo hiciera cada cliente, la
                // vista previa de la web y la del móvil mostrarían tamaños
                // distintos para la misma plantilla.
                // Se redondea a la micra: `6 * 25.4` da `152.39999999999998` en
                // coma flotante, y comparar exacto haría fallar la prueba por cómo
                // representa el ordenador los decimales, no por el código. Una
                // micra es mucho más fina que cualquier uso del dato.
                Math.round(preview.preview.widthMm * 1000) / 1000.0 shouldBe 101.6
                Math.round(preview.preview.heightMm * 1000) / 1000.0 shouldBe 152.4
            }
        }
    }
})

/** Datos completos para la plantilla de prueba. */
private fun datosCompletos() = mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA")
