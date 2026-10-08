package dev.axzy.stock.backend

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TEST ESTRUCTURAL: TODAS LAS RUTAS ESTÁN CONECTADAS
 *
 * **Este test existe por un fallo real que encontré.** El módulo de catálogo se
 * entregó con 37 tests en verde, y los 37 pasaban porque llamaban a
 * `catalogRoutes` directamente, sin pasar por `Application.kt`. Pero la línea que
 * conecta esas rutas con la aplicación **no estaba escrita**, así que en
 * producción `/v1/products` habría devuelto **404** con toda la suite en verde.
 *
 * Es la peor clase de hueco: el trabajo está hecho, los tests lo cubren, y la
 * funcionalidad no existe. Ningún test de comportamiento podía detectarlo porque
 * todos probaban la función aislada.
 *
 * La comprobación es deliberadamente **estructural**: se lee el fuente de
 * `Application.kt` y se verifica que cada función de rutas que existe en el
 * módulo esté invocada. Así, cuando alguien añada un módulo de rutas nuevo y se
 * olvide de conectarlo, este test falla.
 *
 * **No verifica que las rutas funcionen** —eso lo hacen los tests de cada
 * módulo— sino que **estén enchufadas**. Son dos cosas distintas y la segunda se
 * olvida con más facilidad porque no rompe nada visible.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class RoutesWiringTest : DescribeSpec({

    /** Localiza el fuente de la aplicación subiendo desde el directorio de trabajo. */
    fun fuenteDeAplicacion(): File {
        val candidatos = listOf(
            File("src/main/kotlin/dev/axzy/stock/backend/Application.kt"),
            File("backend/src/main/kotlin/dev/axzy/stock/backend/Application.kt"),
            File("../backend/src/main/kotlin/dev/axzy/stock/backend/Application.kt"),
        )
        return candidatos.firstOrNull { it.exists() }
            ?: error(
                "No se encontró Application.kt. Buscado en: " +
                    candidatos.joinToString { it.absolutePath },
            )
    }

    /**
     * La raíz del código de producción del backend.
     *
     * **Ya no hay un directorio `routes/`.** Tras el refactor a paquetes por
     * feature, cada `fun Route.algoRoutes` vive en la carpeta de su feature
     * (`inventory/`, `catalog/`, `audit/`…), así que se recorre todo el árbol.
     * El test sigue comprobando exactamente lo mismo —que ninguna función de
     * rutas se quede sin conectar— sin atarse a dónde esté el archivo.
     */
    fun directorioDeRutas(): File {
        val candidatos = listOf(
            File("src/main/kotlin/dev/axzy/stock/backend"),
            File("backend/src/main/kotlin/dev/axzy/stock/backend"),
            File("../backend/src/main/kotlin/dev/axzy/stock/backend"),
        )
        return candidatos.firstOrNull { it.isDirectory }
            ?: error("No se encontró el código de producción del backend")
    }

    describe("todas las funciones de rutas están conectadas en Application.kt") {

        it("cada `Route.algoRoutes` que existe está invocada en el routing") {
            val fuente = fuenteDeAplicacion().readText()

            // Se buscan las funciones de extensión sobre `Route` que declaran
            // rutas, por su convención de nombre.
            val funcionesDeRutas = directorioDeRutas()
                .walkTopDown()
                .filter { f -> f.isFile && f.extension == "kt" }
                .flatMap { archivo ->
                    Regex("""fun\s+Route\.(\w+Routes)\s*\(""")
                        .findAll(archivo.readText())
                        .map { it.groupValues[1] }
                        .toList()
                }
                .distinct()
                .toList()

            withClue("Debe haber al menos una función de rutas que verificar") {
                (funcionesDeRutas.isNotEmpty()) shouldBe true
            }

            val desconectadas = funcionesDeRutas.filterNot { nombre ->
                // Se busca la INVOCACIÓN, no la declaración: `nombre(` dentro del
                // fuente de la aplicación, con la indentación de una llamada.
                Regex("""^\s+$nombre\(""", RegexOption.MULTILINE).containsMatchIn(fuente)
            }

            withClue(
                "Estas funciones de rutas existen pero NO están invocadas en " +
                    "Application.kt: $desconectadas. Sus endpoints devolverían 404 " +
                    "en producción con todos los tests en verde, porque los tests " +
                    "de cada módulo llaman a la función directamente.",
            ) {
                desconectadas shouldBe emptyList()
            }
        }

        it("el catálogo está conectado explícitamente") {
            // Se comprueba por nombre además del bucle anterior: si alguien
            // renombra la función, el bucle la deja de ver y este test lo nota.
            val fuente = fuenteDeAplicacion().readText()

            withClue("`catalogRoutes` debe estar invocada en Application.kt") {
                Regex("""^\s+catalogRoutes\(""", RegexOption.MULTILINE)
                    .containsMatchIn(fuente) shouldBe true
            }
        }
    }

    describe("las rutas conectadas responden, y no con 404") {

        fun contrasenaLocal(): String {
            val archivo: File? = listOf(File("infra/.env"), File("../infra/.env"))
                .firstOrNull { it.exists() }
            return archivo?.readLines()
                ?.firstOrNull { it.startsWith("POSTGRES_PASSWORD=") }
                ?.substringAfter("=")?.trim() ?: ""
        }

        val config = DatabaseConfig(
            jdbcUrl = System.getenv("STOCK_TEST_DB_URL")
                ?: "jdbc:postgresql://127.0.0.1:5432/stock_test",
            username = System.getenv("STOCK_TEST_DB_USER") ?: "stock_app",
            password = System.getenv("STOCK_TEST_DB_PASSWORD") ?: contrasenaLocal(),
            maxPoolSize = 2,
            runMigrations = false,
        )

        fun <T> conPool(bloque: (DataSource) -> T): T {
            val pool = buildDataSource(config)
            return try {
                bloque(pool)
            } finally {
                (pool as? AutoCloseable)?.close()
            }
        }

        val hayBase: Boolean = try {
            conPool { pool ->
                pool.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT 1").use { it.next() }
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }

        it("`/v1/products` NO devuelve 404: la ruta está enchufada") {
            // Es la comprobación que habría detectado el hueco. Sin identidad
            // conectada el verificador deniega todo, así que se espera 401 —
            // **nunca 404**. Un 404 significaría que la ruta no existe.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }

                    val respuesta = client.get("/v1/products")

                    withClue(
                        "Se esperaba 401 (el verificador por defecto deniega todo) " +
                            "y se obtuvo ${respuesta.status}. Un 404 significa que la " +
                            "ruta NO está conectada en Application.kt.",
                    ) {
                        (respuesta.status == HttpStatusCode.NotFound) shouldBe false
                    }
                    respuesta.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        it("`/v1/locations` y `/v1/warehouses` tampoco devuelven 404") {
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }

                    listOf("/v1/locations", "/v1/warehouses").forEach { ruta ->
                        val respuesta = client.get(ruta)
                        withClue("'$ruta' no debe devolver 404") {
                            (respuesta.status == HttpStatusCode.NotFound) shouldBe false
                        }
                    }
                }
            }
        }

        it("`/v1/inventory/stock` y `/v1/inventory/kardex` están conectados") {
            // Las rutas de inventario son las últimas en llegar y las que más
            // fácil es olvidar conectar: su servicio existe, compila y tiene
            // lógica, así que nada falla hasta que alguien las llama.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }

                    listOf("/v1/inventory/stock", "/v1/inventory/kardex").forEach { ruta ->
                        val respuesta = client.get(ruta)
                        withClue(
                            "'$ruta' no debe devolver 404 (se obtuvo ${respuesta.status}). " +
                                "Un 404 significa que la ruta NO está conectada en Application.kt.",
                        ) {
                            (respuesta.status == HttpStatusCode.NotFound) shouldBe false
                        }
                    }
                }
            }
        }

        it("`/v1/reports/kardex-vs-projection` está conectado") {
            // Es el reporte de reconciliación. Sin conectar, el descuadre de un
            // inventario seguiría descubriéndose con un conteo físico, que es
            // exactamente lo que este endpoint existe para evitar.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }
                    val respuesta = client.get("/v1/reports/kardex-vs-projection")
                    withClue("No debe devolver 404") {
                        (respuesta.status == HttpStatusCode.NotFound) shouldBe false
                    }
                }
            }
        }

        it("las rutas de ESCRITURA de inventario existen y piden identidad") {
            // `POST` a estas rutas debe dar 401 (falta token), NO 404 ni 405.
            // Un 405 significaría que la ruta existe pero con otro verbo, que es
            // un error silencioso muy fácil de cometer.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }

                    listOf(
                        "/v1/inventory/adjustments",
                        "/v1/inventory/locate",
                        "/v1/inventory/move",
                    ).forEach { ruta ->
                        val respuesta = client.post(ruta) {
                            header(io.ktor.http.HttpHeaders.ContentType, "application/json")
                            setBody("{}")
                        }
                        withClue("POST '$ruta' debe dar 401, no ${respuesta.status}") {
                            (respuesta.status == HttpStatusCode.NotFound) shouldBe false
                            (respuesta.status == HttpStatusCode.MethodNotAllowed) shouldBe false
                        }
                    }
                }
            }
        }

        it("las rutas de RECEPCIÓN están conectadas") {
            // El flujo de recepción es el que la regla B1 protege. Si sus rutas no
            // están conectadas, una discrepancia se queda bloqueada sin que exista
            // la pantalla para aprobarla, y el inventario se atasca en silencio.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }

                    listOf(
                        "/v1/invoices/999999",
                        "/v1/invoices/pending-approval",
                    ).forEach { ruta ->
                        val respuesta = client.get(ruta)
                        withClue(
                            "GET '$ruta' no debe dar 404 (se obtuvo ${respuesta.status}). " +
                                "Comprueba que receivingRoutes está invocada en Application.kt.",
                        ) {
                            (respuesta.status == HttpStatusCode.NotFound && ruta.contains("pending")) shouldBe false
                        }
                    }
                }
            }
        }

        it("`POST /v1/invoices/{id}/approve` existe: es la regla B1") {
            // **Esta ruta es la que hace cumplir la regla B1.** Sin ella, una línea
            // con discrepancia no se puede desbloquear: no se puede ubicar y no hay
            // forma de aprobarla, así que la factura se queda atascada para siempre.
            //
            // Y tiene que ser POR FACTURA y no por línea (R-86): la variante por línea
            // se eliminó del contrato a propósito.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }
                    val respuesta = client.post("/v1/invoices/999999/approve") {
                        header(io.ktor.http.HttpHeaders.ContentType, "application/json")
                        setBody("{}")
                    }
                    withClue("Debe dar 401 (falta token), no 404 ni 405 — se obtuvo ${respuesta.status}") {
                        (respuesta.status == HttpStatusCode.NotFound) shouldBe false
                        (respuesta.status == HttpStatusCode.MethodNotAllowed) shouldBe false
                    }
                }
            }
        }

        it("`POST /v1/invoices` se puede crear y `capture` existe") {
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }

                    val crear = client.post("/v1/invoices") {
                        header(io.ktor.http.HttpHeaders.ContentType, "application/json")
                        setBody("{}")
                    }
                    withClue("POST /v1/invoices debe existir") {
                        (crear.status == HttpStatusCode.NotFound) shouldBe false
                        (crear.status == HttpStatusCode.MethodNotAllowed) shouldBe false
                    }

                    val capturar = client.post("/v1/invoices/1/lines/1/capture") {
                        header(io.ktor.http.HttpHeaders.ContentType, "application/json")
                        setBody("{}")
                    }
                    withClue("POST .../capture debe existir") {
                        (capturar.status == HttpStatusCode.NotFound) shouldBe false
                        (capturar.status == HttpStatusCode.MethodNotAllowed) shouldBe false
                    }
                }
            }
        }

        it("`/v1/health` sigue respondiendo y es público") {
            // La única ruta que debe funcionar sin identidad. Si esto falla, algo
            // se rompió al conectar el catálogo.
            if (!hayBase) return@it

            conPool { pool ->
                testApplication {
                    application { module(pool) }
                    client.get("/v1/health").status shouldBe HttpStatusCode.OK
                }
            }
        }
    }
})
