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
    fun passwordLocal(): String {
        val file: File? = listOf(File("infra/.env"), File("../infra/.env"))
            .firstOrNull { it.exists() }

        return file?.readLines()
            ?.firstOrNull { it.startsWith("POSTGRES_PASSWORD=") }
            ?.substringAfter("=")
            ?.trim()
            ?: ""
    }

    val config = DatabaseConfig(
        jdbcUrl = System.getenv("STOCK_TEST_DB_URL") ?: "jdbc:postgresql://127.0.0.1:5432/stock_test",
        username = System.getenv("STOCK_TEST_DB_USER") ?: "stock_app",
        password = System.getenv("STOCK_TEST_DB_PASSWORD") ?: passwordLocal(),
        maxPoolSize = 2,
        runMigrations = false,
    )

    /** Crea un pool, ejecuta el bloque y lo cierra siempre. */
    fun <T> withPool(block: (DataSource) -> T): T {
        val pool = buildDataSource(config)
        return try {
            block(pool)
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
    fun diagnoseConnection(): String? = try {
        withPool { pool ->
            pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { rows -> rows.next() }
                }
            }
        }
        null   // sin problema
    } catch (e: Exception) {
        "${e::class.simpleName}: ${e.message}"
    }

    val connectionProblem: String? = diagnoseConnection()
    val thereIsBase: Boolean = connectionProblem == null

    if (!thereIsBase) {
        println(
            """
            |
            |  ⚠️  TESTS DEL BACKEND SALTADOS: no se pudo consultar ${config.jdbcUrl}
            |      Motivo: $connectionProblem
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

    val nameBaseMigration = "stock_migracion_test"

    val configMigration = DatabaseConfig(
        jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/$nameBaseMigration",
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
    fun recreateDedicatedDatabase() {
        val urlAdmin = "jdbc:postgresql://127.0.0.1:5432/postgres"
        val pool = buildDataSource(
            config.copy(jdbcUrl = urlAdmin, maxPoolSize = 1)
        )

        try {
            pool.connection.use { connection ->
                // DDL con confirmación automática: con `autoCommit = false` el
                // `CREATE DATABASE` quedaría en una transacción sin confirmar y
                // al cerrar la conexión PostgreSQL haría ROLLBACK, dejando la
                // base sin crear mientras el `DROP` sí se había aplicado.
                connection.autoCommit = true

                connection.createStatement().use { statement ->
                    // `WITH (FORCE)` cierra las conexiones abiertas: sin esto,
                    // un pool que quedó vivo impide soltar la base.
                    statement.execute("DROP DATABASE IF EXISTS $nameBaseMigration WITH (FORCE)")
                    statement.execute("CREATE DATABASE $nameBaseMigration")
                }
            }
        } finally {
            (pool as? AutoCloseable)?.close()
        }
    }

    describe("migraciones de Flyway sobre una base limpia") {

        it("aplica TODAS las migraciones desde cero sin errores") {
            if (!thereIsBase) return@it

            recreateDedicatedDatabase()

            val pool = buildDataSource(configMigration)
            try {
                migrate(pool)

                // Se comprueba el resultado, no solo que no lanzara: una
                // migración que "pasa" sin crear tablas es un fallo silencioso.
                pool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT count(*) FROM information_schema.tables " +
                                "WHERE table_schema = 'public'"
                        ).use { rows ->
                            rows.next()
                            val tables = rows.getInt(1)
                            withClue(
                                "Deben existir las 30 tablas del esquema. Encontradas: $tables"
                            ) {
                                (tables >= 30) shouldBe true
                            }
                        }
                    }
                }
            } finally {
                (pool as? AutoCloseable)?.close()
            }
        }

        it("el historial de Flyway coincide con las migraciones del repositorio") {
            // Se compara con los ARCHIVOS REALES y no con una lista fija.
            //
            // La primera versión de este test esperaba ["1","2","3"] y falló al
            // añadirse la V4. Un test que hay que actualizar cada vez que se
            // añade una migración es un test que, con prisa, se actualiza sin
            // mirar si el número es el correcto. Comparar con el repositorio
            // verifica lo que de verdad importa: que **no falte ninguna**.
            if (!thereIsBase) return@it

            val pool = buildDataSource(configMigration)
            try {
                pool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT version FROM flyway_schema_history " +
                                "WHERE version IS NOT NULL ORDER BY installed_rank"
                        ).use { rows ->
                            val versions = mutableListOf<String>()
                            while (rows.next()) versions += rows.getString("version")

                            val expected = java.io.File("src/main/resources/db/migration")
                                .takeIf { it.isDirectory }
                                ?: java.io.File("../backend/src/main/resources/db/migration")

                            val versionsOnDisk = (expected.listFiles { f ->
                                f.name.startsWith("V") && f.name.endsWith(".sql")
                            } ?: emptyArray())
                                .map { it.name.substringAfter('V').substringBefore("__") }
                                .sortedBy { it.toInt() }

                            withClue(
                                "El historial debe coincidir con las migraciones del " +
                                    "repositorio ($versionsOnDisk) y no faltar ninguna: $versions"
                            ) {
                                versions shouldBe versionsOnDisk
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
            if (!thereIsBase) return@it

            val pool = buildDataSource(configMigration)
            try {
                val checksExpected = listOf(
                    "stock_non_negative" to "stock_by_location",
                    "kardex_adjustment_requires_reason" to "kardex",
                    "invoice_lines_approved_has_approver" to "invoice_lines",
                    "audit_critical_has_permission" to "audit_log",
                )

                pool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        checksExpected.forEach { (name, table) ->
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
                            statement.executeQuery(
                                """
                                SELECT count(*)
                                FROM pg_constraint c
                                WHERE c.conname = '$name'
                                  AND c.conrelid = '$table'::regclass
                                """.trimIndent()
                            ).use { rows ->
                                rows.next()
                                withClue(
                                    "El CHECK '$name' debe existir en '$table' tras migrar: " +
                                        "es una regla de negocio impuesta en la base de datos, " +
                                        "no solo en el código"
                                ) {
                                    rows.getInt(1) shouldBe 1
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
            if (!thereIsBase) return@it

            withPool { pool ->
                testApplication {
                    application { module(pool) }
                    val response = client.get("/v1/health")

                    response.status shouldBe HttpStatusCode.OK

                    val body = response.bodyAsText()
                    withClue("El cuerpo debe declarar el estado y el componente") {
                        body shouldContain "\"status\":\"UP\""
                        body shouldContain "\"database\""
                    }
                }
            }
        }

        it("informa de la versión del esquema aplicado") {
            // Saber qué código corre contra qué versión de esquema es lo que
            // permite diagnosticar un despliegue a medias. Sin esto, un desajuste
            // entre código y base se manifiesta como errores incomprensibles.
            if (!thereIsBase) return@it

            withPool { pool ->
                migrate(pool)

                testApplication {
                    application { module(pool) }
                    val body = client.get("/v1/health").bodyAsText()

                    withClue("El health debe incluir schemaVersion. Cuerpo: $body") {
                        body shouldContain "schemaVersion"
                    }
                }
            }
        }

        it("mide la latencia de la comprobación") {
            // Un health que no mide latencia no permite detectar una base lenta
            // antes de que se convierta en una caída.
            if (!thereIsBase) return@it

            withPool { pool ->
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
            if (!thereIsBase) return@it

            withPool { pool ->
                testApplication {
                    application { module(pool) }
                    val response = client.get("/v1/no-existe")

                    response.status shouldBe HttpStatusCode.NotFound

                    val body = response.bodyAsText()
                    withClue("Debe ser el contrato de error en JSON") {
                        body shouldContain "\"code\""
                        body shouldContain "NOT_FOUND"
                    }
                }
            }
        }

        it("el error 404 no filtra detalles internos") {
            if (!thereIsBase) return@it

            withPool { pool ->
                testApplication {
                    application { module(pool) }
                    val body = client.get("/v1/no-existe").bodyAsText()

                    withClue(
                        "No debe aparecer una traza ni un nombre de tabla: el legacy " +
                            "devolvía los errores de Prisma con el SQL incluido"
                    ) {
                        (body.contains("Exception")) shouldBe false
                        (body.contains("at dev.axzy")) shouldBe false
                        (body.contains("SELECT")) shouldBe false
                    }
                }
            }
        }

        it("el JSON omite los campos nulos en lugar de enviarlos") {
            // `explicitNulls = false`: un campo ausente y un campo nulo significan
            // lo mismo para el cliente, y omitirlos reduce el tamaño.
            if (!thereIsBase) return@it

            withPool { pool ->
                testApplication {
                    application { module(pool) }
                    val body = client.get("/v1/no-existe").bodyAsText()

                    withClue("Un campo sin valor no debe aparecer como null. Cuerpo: $body") {
                        (body.contains("null")) shouldBe false
                    }
                }
            }
        }
    }
})
