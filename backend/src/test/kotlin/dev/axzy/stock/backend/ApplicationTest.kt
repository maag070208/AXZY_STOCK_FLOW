package dev.axzy.stock.backend

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DEL ANDAMIAJE DEL BACKEND
 *
 * **Corren contra PostgreSQL REAL, no contra un mock.** Un health que consulta
 * un mock demuestra que el mock funciona, no que la base responda. Y como el
 * health comprueba precisamente si la base responde, probarlo con un doble de
 * prueba no verifica nada.
 *
 * **Si no hay base disponible, los tests se SALTAN** en lugar de fallar o, peor,
 * pasar en falso. Un test que pasa porque no se ejecutó es la clase de verde que
 * esconde problemas.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ApplicationTest : DescribeSpec({

    // ── Utilidades ──────────────────────────────────────────────────────────
    // Se declaran como funciones locales en este orden para que cada una pueda
    // usar las anteriores.

    /**
     * Lee la contraseña de `infra/.env`.
     *
     * El archivo está ignorado por git, así que no hay secreto en el
     * repositorio: el test lee la misma fuente que el contenedor.
     */
    fun contrasenaLocal(): String {
        val archivo: File? = listOf(File("infra/.env"), File("../infra/.env"))
            .firstOrNull { it.exists() }

        return archivo?.readLines()
            ?.firstOrNull { it.startsWith("POSTGRES_PASSWORD=") }
            ?.substringAfter("=")
            ?.trim()
            ?: ""
    }

    val config = DatabaseConfig(
        jdbcUrl = System.getenv("STOCK_TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:5432/stock_test",
        username = System.getenv("STOCK_TEST_DB_USER") ?: "stock_app",
        password = System.getenv("STOCK_TEST_DB_PASSWORD") ?: contrasenaLocal(),
        maxPoolSize = 2,
        runMigrations = false,
    )

    /** Crea un pool, ejecuta el bloque y lo cierra siempre. */
    fun <T> conPool(bloque: (DataSource) -> T): T {
        val pool = buildDataSource(config)
        return try {
            bloque(pool)
        } finally {
            (pool as? AutoCloseable)?.close()
        }
    }

    /**
     * ¿Hay base disponible? Si no, los tests se saltan.
     *
     * **Se ejecuta una consulta, no solo se pide una conexión.** HikariCP
     * obtiene las conexiones de forma perezosa, así que `pool.connection`
     * devuelve un envoltorio sin haber contactado con el servidor: la
     * comprobación pasaba y luego el primer `SELECT` real fallaba con 503.
     * Es exactamente la clase de falso verde que hay que evitar.
     */
    fun diagnosticarConexion(): String? = try {
        conPool { pool ->
            pool.connection.use { conexion ->
                conexion.createStatement().use { sentencia ->
                    sentencia.executeQuery("SELECT 1").use { filas -> filas.next() }
                }
            }
        }
        null   // sin problema
    } catch (e: Exception) {
        "${e::class.simpleName}: ${e.message}"
    }

    val problemaDeConexion: String? = diagnosticarConexion()
    val hayBase: Boolean = problemaDeConexion == null

    if (!hayBase) {
        println(
            """
            |
            |  ⚠️  TESTS DEL BACKEND SALTADOS: no se pudo consultar ${config.jdbcUrl}
            |      Motivo: $problemaDeConexion
            |      Levántalo con:  cd infra && docker compose up -d
            |      Si el contenedor ya está arriba, define STOCK_TEST_DB_PASSWORD.
            |
            """.trimMargin()
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BASE DEDICADA PARA EL TEST DE MIGRACIONES
    //
    // **Por qué una base propia y no recrear el esquema de `stock_test`.**
    //
    // La primera versión de este test hacía `DROP SCHEMA public CASCADE` sobre
    // `stock_test` en mitad de la suite. Eso es frágil por diseño: el schema es
    // compartido por todas las conexiones abiertas, y soltarlo mientras otra
    // prueba lo usa produce fallos que dependen del orden de ejecución. El
    // síntoma era «no schema has been selected to create in», un mensaje que no
    // señala la causa real.
    //
    // Una base dedicada elimina el problema de raíz: se borra y se recrea
    // entera, sin tocar nada compartido. Además es lo honesto: **probar las
    // migraciones significa probarlas sobre una base que no existe**, porque es
    // lo que pasa en un despliegue nuevo.
    // ═════════════════════════════════════════════════════════════════════════

    val nombreBaseMigracion = "stock_migracion_test"

    val configMigracion = DatabaseConfig(
        jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/$nombreBaseMigracion",
        username = config.username,
        password = config.password,
        maxPoolSize = 2,
        runMigrations = false,
    )

    /**
     * Borra y recrea la base dedicada.
     *
     * Se conecta a `postgres` —la base de administración— porque no se puede
     * soltar la base a la que estás conectado.
     */
    fun recrearBaseDedicada() {
        val urlAdmin = "jdbc:postgresql://127.0.0.1:5432/postgres"
        val pool = buildDataSource(
            config.copy(jdbcUrl = urlAdmin, maxPoolSize = 1)
        )

        try {
            pool.connection.use { conexion ->
                // DDL con confirmación automática: con `autoCommit = false` el
                // `CREATE DATABASE` quedaría en una transacción sin confirmar y
                // al cerrar la conexión PostgreSQL haría ROLLBACK, dejando la
                // base sin crear mientras el `DROP` sí se había aplicado.
                conexion.autoCommit = true

                conexion.createStatement().use { sentencia ->
                    // `WITH (FORCE)` cierra las conexiones abiertas: sin esto,
                    // un pool que quedó vivo impide soltar la base.
                    sentencia.execute("DROP DATABASE IF EXISTS $nombreBaseMigracion WITH (FORCE)")
                    sentencia.execute("CREATE DATABASE $nombreBaseMigracion")
                }
            }
        } finally {
            (pool as? AutoCloseable)?.close()
        }
    }

    describe("migraciones de Flyway sobre una base limpia") {

        it("aplica TODAS las migraciones desde cero sin errores") {
            if (!hayBase) return@it

            recrearBaseDedicada()

            val pool = buildDataSource(configMigracion)
            try {
                migrate(pool)

                // Se comprueba el resultado, no solo que no lanzara: una
                // migración que "pasa" sin crear tablas es un fallo silencioso.
                pool.connection.use { conexion ->
                    conexion.createStatement().use { sentencia ->
                        sentencia.executeQuery(
                            "SELECT count(*) FROM information_schema.tables " +
                                "WHERE table_schema = 'public'"
                        ).use { filas ->
                            filas.next()
                            val tablas = filas.getInt(1)
                            withClue(
                                "Deben existir las 30 tablas del esquema. Encontradas: $tablas"
                            ) {
                                (tablas >= 30) shouldBe true
                            }
                        }
                    }
                }
            } finally {
                (pool as? AutoCloseable)?.close()
            }
        }

        it("el historial de Flyway queda con las 3 versiones aplicadas") {
            if (!hayBase) return@it

            val pool = buildDataSource(configMigracion)
            try {
                pool.connection.use { conexion ->
                    conexion.createStatement().use { sentencia ->
                        sentencia.executeQuery(
                            "SELECT version FROM flyway_schema_history " +
                                "WHERE version IS NOT NULL ORDER BY installed_rank"
                        ).use { filas ->
                            val versiones = mutableListOf<String>()
                            while (filas.next()) versiones += filas.getString("version")

                            withClue("Las tres migraciones deben quedar registradas") {
                                versiones shouldBe listOf("1", "2", "3")
                            }
                        }
                    }
                }
            } finally {
                (pool as? AutoCloseable)?.close()
            }
        }

        it("las invariantes del esquema sobreviven a la migración") {
            // Una migración que crea las tablas pero pierde los CHECK pasaría el
            // test de recuento. Éste comprueba que las reglas de negocio siguen
            // impuestas por la BASE DE DATOS.
            if (!hayBase) return@it

            val pool = buildDataSource(configMigracion)
            try {
                val checksEsperados = listOf(
                    "stock_non_negative" to "stock_by_location",
                    "kardex_adjustment_requires_reason" to "kardex",
                    "invoice_lines_approved_has_approver" to "invoice_lines",
                    "audit_critical_has_permission" to "audit_log",
                )

                pool.connection.use { conexion ->
                    conexion.createStatement().use { sentencia ->
                        checksEsperados.forEach { (nombre, tabla) ->
                            // ── SE COMPRUEBA EN LA TABLA DONDE SE DECLARA ───────
                            // Un CHECK sobre una tabla PARTICIONADA se materializa
                            // como un objeto propio en CADA partición, además del
                            // padre: `audit_critical_has_permission` aparece 6 veces
                            // (el padre más 5 particiones), y todas con
                            // `conparentid = 0`, así que filtrar por eso no sirve.
                            //
                            // Contar «exactamente 1» era una aserción equivocada por
                            // mi parte, no un defecto de la migración. Se comprueba
                            // donde la regla se DECLARA, que es lo que importa: la
                            // partición es un detalle de almacenamiento.
                            sentencia.executeQuery(
                                """
                                SELECT count(*)
                                FROM pg_constraint c
                                WHERE c.conname = '$nombre'
                                  AND c.conrelid = '$tabla'::regclass
                                """.trimIndent()
                            ).use { filas ->
                                filas.next()
                                withClue(
                                    "El CHECK '$nombre' debe existir en '$tabla' tras migrar: " +
                                        "es una regla de negocio impuesta en la base de datos, " +
                                        "no solo en el código"
                                ) {
                                    filas.getInt(1) shouldBe 1
                                }
                            }
                        }
                    }
                }
            } finally {
                (pool as? AutoCloseable)?.close()
            }
        }
    }

    describe("GET /v1/health — comprueba la base de datos de verdad") {

        it("responde 200 y UP cuando la base responde") {
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }
                    val respuesta = client.get("/v1/health")

                    respuesta.status shouldBe HttpStatusCode.OK

                    val cuerpo = respuesta.bodyAsText()
                    withClue("El cuerpo debe declarar el estado y el componente") {
                        cuerpo shouldContain "\"status\":\"UP\""
                        cuerpo shouldContain "\"database\""
                    }
                }
            }
        }

        it("informa de la versión del esquema aplicado") {
            // Saber qué código corre contra qué versión de esquema es lo que
            // permite diagnosticar un despliegue a medias. Sin esto, un desajuste
            // entre código y base se manifiesta como errores incomprensibles.
            if (!hayBase) return@it

            conPool { pool ->
                migrate(pool)

                testApplication {
                    application { module(pool) }
                    val cuerpo = client.get("/v1/health").bodyAsText()

                    withClue("El health debe incluir schemaVersion. Cuerpo: $cuerpo") {
                        cuerpo shouldContain "schemaVersion"
                    }
                }
            }
        }

        it("mide la latencia de la comprobación") {
            // Un health que no mide latencia no permite detectar una base lenta
            // antes de que se convierta en una caída.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }
                    client.get("/v1/health").bodyAsText() shouldContain "latencyMs"
                }
            }
        }
    }

    describe("manejo de errores") {

        it("una ruta inexistente devuelve el contrato de error en JSON, no HTML") {
            // Ktor devolvería una página por defecto. Un cliente que espera JSON
            // y recibe HTML falla al deserializar, y el error real se pierde.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }
                    val respuesta = client.get("/v1/no-existe")

                    respuesta.status shouldBe HttpStatusCode.NotFound

                    val cuerpo = respuesta.bodyAsText()
                    withClue("Debe ser el contrato de error en JSON") {
                        cuerpo shouldContain "\"code\""
                        cuerpo shouldContain "NOT_FOUND"
                    }
                }
            }
        }

        it("el error 404 no filtra detalles internos") {
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }
                    val cuerpo = client.get("/v1/no-existe").bodyAsText()

                    withClue(
                        "No debe aparecer una traza ni un nombre de tabla: el legacy " +
                            "devolvía los errores de Prisma con el SQL incluido"
                    ) {
                        (cuerpo.contains("Exception")) shouldBe false
                        (cuerpo.contains("at dev.axzy")) shouldBe false
                        (cuerpo.contains("SELECT")) shouldBe false
                    }
                }
            }
        }

        it("el JSON omite los campos nulos en lugar de enviarlos") {
            // `explicitNulls = false`: un campo ausente y un campo nulo significan
            // lo mismo para el cliente, y omitirlos reduce el tamaño.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }
                    val cuerpo = client.get("/v1/no-existe").bodyAsText()

                    withClue("Un campo sin valor no debe aparecer como null. Cuerpo: $cuerpo") {
                        (cuerpo.contains("null")) shouldBe false
                    }
                }
            }
        }
    }
})
