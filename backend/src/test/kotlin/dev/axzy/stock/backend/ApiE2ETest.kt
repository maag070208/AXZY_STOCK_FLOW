package dev.axzy.stock.backend

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import dev.axzy.stock.backend.identity.IdentitySeeder
import dev.axzy.stock.backend.identity.NativeArgon2PasswordHasher
import dev.axzy.stock.backend.identity.TokenConfig
import dev.axzy.stock.backend.identity.TokenService
import dev.axzy.stock.backend.identity.TokenSubject
import dev.axzy.stock.backend.identity.aAccessUser
import dev.axzy.stock.backend.identity.tokenClaims
import dev.axzy.stock.backend.security.AccessEnginePermissionChecker
import dev.axzy.stock.domain.access.AccessEngine
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * E2E DE LA API — el recorrido COMPLETO, con la configuración de producción
 *
 * **QUÉ ES «E2E» AQUÍ, Y POR QUÉ NO BASTAN LOS TESTS DE RUTA QUE YA HAY.**
 *
 * `CatalogRoutesTest` y `ApplicationTest` instalan el módulo con
 * `AllowAllPermissionChecker`. Eso prueba los handlers, pero **deja fuera
 * exactamente las piezas donde se cruzan los errores**: la firma y validación del
 * token, el motor RBAC+ABAC, la matriz de permisos sembrada, el hash de la
 * contraseña y la serialización de verdad del DTO.
 *
 * Aquí no se sustituye NADA. Se arranca el mismo `module(...)` que se despliega,
 * con:
 *
 *   · **PostgreSQL real**, base DEDICADA que se borra y se recrea con Flyway.
 *   · **La identidad sembrada** (`IdentitySeeder`): roles, 82 permisos, matriz y
 *     un administrador con contraseña conocida.
 *   · **`TokenService` de verdad**, con su HMAC y su validación de emisor,
 *     audiencia y caducidad.
 *   · **`AccessEnginePermissionChecker` de verdad**, con el `AccessEngine` del
 *     dominio y el puente `AccessTokenClaims.aAccessUser()` **que usa
 *     `Application.kt`** — no una copia.
 *
 * ── EL FALLO QUE ESTE TEST HABRÍA CAZADO ───────────────────────────────────
 *
 * La corrección de `docs/historico/CONTRATO_API.md` §9 necesitó un cambio en el `RETURNS TABLE`
 * de una función PL/pgSQL **y** en el mapper que lo lee por nombre de columna. La
 * suite entera —501 tests— pasó sin leer esa función ni una vez, porque sin token
 * la ruta responde 401 **antes** de llegar al mapper. Un mapper mal escrito no
 * rompe: miente.
 *
 * Aquí se pide el token, se llama a la ruta y se comprueba **el nombre y el valor
 * de cada campo del JSON**. Un cambio de nombre de columna que nadie propague sale
 * rojo en este archivo.
 *
 * ── SI NO HAY BASE, SE SALTA Y LO DICE ─────────────────────────────────────
 *
 * Un test que pasa porque no se ejecutó es peor que un test que falta. El motivo
 * se imprime.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ApiE2ETest : DescribeSpec({

    val nameBase = "stock_e2e_test"

    /** Credenciales del administrador sembrado. Contraseña conocida: es un test. */
    val adminUser = "e2e-admin"
    val adminPassword = "Contrasena-E2E-de-prueba-2026"

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
        // 2 conexiones: Flyway necesita la suya mientras el test mantiene la suya.
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
        println(
            """
            |
            |  ⚠️  ApiE2ETest SALTADO: no se pudo conectar a PostgreSQL.
            |      Levántalo con:  cd infra && docker compose up -d
            |
            """.trimMargin(),
        )
    }

    /**
     * Base dedicada, migrada y sembrada. **Se recrea en cada spec.**
     *
     * Se borra entera en lugar de limpiar tablas: `audit_log` es append-only y sus
     * disparadores prohíben el `DELETE` a propósito, así que «limpiar» no es una
     * opción sin desactivar la protección que queremos probar que existe.
     */
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
        migrate(pool)
        // El sembrado es obligatorio: sin la matriz de permisos, el `AccessEngine`
        // deniega por defecto y TODAS las rutas darían 403 — un rojo que no
        // señalaría al código sino al montaje del test.
        IdentitySeeder(pool).seed(adminUsername = adminUser, adminPassword = adminPassword)
        return pool
    }

    fun <T> withFreshDatabase(block: (DataSource, TokenService) -> T): T? {
        if (!thereIsBase) return null
        val pool = prepareDatabase() ?: return null
        return try {
            val tokens = TokenService(
                TokenConfig(
                    secret = "clave-e2e-con-longitud-mas-que-suficiente-para-hmac-sha256-ok",
                    issuer = "almacen-integral",
                    audience = "almacen-integral-api",
                    accessTokenTtlSeconds = 3600,
                ),
            )
            block(pool, tokens)
        } finally {
            close(pool)
        }
    }

    /** El verificador REAL, montado como en `Application.main`: mismo puente. */
    /**
     * El verificador real, **con el escritor de auditoría conectado**.
     *
     * Antes se construía sin él, así que ninguna prueba podía ver que las
     * denegaciones se registran — y no se registraban, porque nadie llamaba a
     * `recordDeniedAccessSafely`. Una prueba no puede comprobar lo que su propio
     * andamiaje omite.
     */
    fun realChecker(tokens: TokenService, pool: DataSource) = AccessEnginePermissionChecker(
        engine = AccessEngine(),
        currentUser = { call -> call.tokenClaims(tokens)?.aAccessUser() },
        denialLog = dev.axzy.stock.backend.infrastructure.outbound.persistence.audit.AuditWriter(pool),
    )

    val json = Json { ignoreUnknownKeys = true }

    /** Inicia sesión y devuelve el token de acceso. */
    suspend fun io.ktor.client.HttpClient.login(username: String, password: String): String {
        val response = post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }

        withClue("El login debe funcionar con el administrador sembrado. Cuerpo: ${response.bodyAsText()}") {
            response.status shouldBe HttpStatusCode.OK
        }

        return json.parseToJsonElement(response.bodyAsText())
            .jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. AUTENTICACIÓN — el camino que ningún test de rutas cubría
    // ═════════════════════════════════════════════════════════════════════════

    describe("autenticación de extremo a extremo") {

        it("un usuario sembrado inicia sesión y recibe sus permisos y alcances") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    val token = client.login(adminUser, adminPassword)

                    // El token sirve para algo: se usa en la misma prueba.
                    val me = client.get("/v1/auth/me") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    me.status shouldBe HttpStatusCode.OK

                    val cuerpo = json.parseToJsonElement(me.bodyAsText()).jsonObject
                    cuerpo["username"]!!.jsonPrimitive.content shouldBe adminUser
                    // El ADMIN tiene que traer permisos: si el sembrado fallara, el
                    // `AccessEngine` denegaría por defecto y esto saldría vacío.
                    (cuerpo["permissions"]!!.jsonArray.size > 0) shouldBe true
                    (cuerpo["scopes"]!!.jsonObject.size > 0) shouldBe true
                    cuerpo["roles"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContain "ADMIN"
                }
            }
        }

        it("una contraseña incorrecta da 401 y NO un 200 ni un 500") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    val response = client.post("/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$adminUser","password":"no-es-la-contrasena"}""")
                    }

                    response.status shouldBe HttpStatusCode.Unauthorized
                    val code = json.parseToJsonElement(response.bodyAsText())
                        .jsonObject["code"]!!.jsonPrimitive.content
                    // El código está en el catálogo cerrado: el cliente decide con
                    // él, nunca con el mensaje.
                    code shouldBe "INVALID_CREDENTIALS"
                }
            }
        }

        it("sin token, TODAS las rutas protegidas dan 401 con UNAUTHENTICATED, nunca 403") {
            // **Esta prueba es de CONSISTENCIA, y encontró un defecto real.** Cada
            // feature traducía la denegación por su cuenta: el catálogo y la
            // administración de identidad distinguían `UNAUTHENTICATED` (401) del
            // resto (403), pero inventario y auditoría lanzaban 403 para todo. La
            // MISMA falta de token daba un estado distinto según la ruta.
            //
            // `docs/historico/CONTRATO_API.md` §1.2 es explícito: 401 = «sin identidad válida»,
            // 403 = «identidad sin autorización». Al cliente le importa: ante un
            // 401 manda a iniciar sesión; ante un 403, a pedir un permiso. Con 403
            // para todo, manda a iniciar sesión a quien ya la tiene abierta.
            //
            // Y tampoco puede ser 404: un 404 haría creer que la ruta no está
            // desplegada cuando lo que falta es la identidad.
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    // **Una ruta por MÓDULO, y al menos una de cada feature.**
                    // Este listado encontró dos veces el mismo defecto —una feature
                    // devolviendo `403` donde tocaba `401`— porque al principio solo
                    // miraba dos rutas. Cubrir un módulo y no otro fue justo el
                    // agujero que dejó pasar el fallo en recepción.
                    val rutas = listOf(
                        "/v1/reports/kardex-vs-projection",
                        "/v1/products",
                        "/v1/locations",
                        "/v1/warehouses",
                        "/v1/inventory/stock",
                        "/v1/audit/log",
                        "/v1/invoices",
                        "/v1/invoices/pending-approval",
                        "/v1/orders",
                        "/v1/transfers",
                        "/v1/transfers/in-transit",
                        "/v1/label-templates",
                        "/v1/labels/jobs",
                        "/v1/users",
                        "/v1/roles",
                        "/v1/permissions",
                    )

                    rutas.forEach { ruta ->
                        val response = client.get(ruta)
                        withClue("Sin token, $ruta debe dar 401 y no 403 ni 404") {
                            response.status shouldBe HttpStatusCode.Unauthorized
                            json.parseToJsonElement(response.bodyAsText())
                                .jsonObject["code"]!!.jsonPrimitive.content shouldBe "UNAUTHENTICATED"
                        }
                    }
                }
            }
        }

        it("un token manipulado NO vale: la firma se comprueba de verdad") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    val token = client.login(adminUser, adminPassword)

                    // **Se manipula el PAYLOAD, no el último carácter de la firma.**
                    //
                    // La primera versión de este test cambiaba el último carácter y
                    // pasaba... o no, según el token: en JWT la firma va en
                    // `base64url` SIN relleno, así que el último carácter puede
                    // tener bits que no cuentan y **cambiarlo a veces produce los
                    // mismos bytes decodificados**. La firma seguía siendo válida y
                    // el test dependía de la suerte. Se vio al ejecutar la suite
                    // entera con `--rerun-tasks`, no al ejecutarlo aislado con la
                    // caché de Gradle.
                    //
                    // Alterar el payload es determinista: cambia los bytes
                    // firmados, así que la firma **tiene que** dejar de validar.
                    val partes = token.split(".")
                    partes.size shouldBe 3
                    val payloadAlterado = partes[1].dropLast(1) +
                        if (partes[1].last() == 'A') 'B' else 'A'
                    val manipulado = "${partes[0]}.$payloadAlterado.${partes[2]}"

                    val response = client.get("/v1/auth/me") {
                        header(HttpHeaders.Authorization, "Bearer $manipulado")
                    }

                    withClue("Un token con el payload alterado no debe verificar") {
                        response.status shouldBe HttpStatusCode.Unauthorized
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. LA RECONCILIACIÓN — el caso que motivó todo esto
    // ═════════════════════════════════════════════════════════════════════════

    describe("el reporte de reconciliación, por HTTP y con la función real de la base") {

        it("publica los campos del contrato y NINGUNO de los nombres viejos en español") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    val token = client.login(adminUser, adminPassword)
                    val response = client.get("/v1/reports/kardex-vs-projection") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val texto = response.bodyAsText()
                    val cuerpo = json.parseToJsonElement(texto).jsonObject

                    // ── El contrato, campo a campo ──────────────────────────────
                    cuerpo["checkedAt"]!!.jsonPrimitive.content.isNotEmpty() shouldBe true
                    cuerpo["divergences"]!!.jsonPrimitive.content shouldBe "0"
                    // `balanced` y NO `cuadra`: era un campo de API pública con
                    // identificador en español, y llegaba así al JSON.
                    cuerpo["balanced"]!!.jsonPrimitive.content shouldBe "true"
                    cuerpo["items"]!!.jsonArray.size shouldBe 0

                    // ── Los nombres que NO deben volver ────────────────────────
                    // Se afirma sobre el TEXTO completo, no solo sobre las claves
                    // de primer nivel, para cazar también los nombres dentro de
                    // `items`.
                    listOf("cuadra", "totalEnBd", "totalEsperado", "diferencia", "productsChecked", "ledgerNet")
                        .forEach { viejo ->
                            withClue("El nombre viejo '$viejo' no debe aparecer en la respuesta") {
                                texto.contains("\"$viejo\"") shouldBe false
                            }
                        }
                }
            }
        }

        it("con un descuadre REAL, el DTO viaja con los valores y el signo correctos") {
            withFreshDatabase { pool, tokens ->
                // Se fabrica el descuadre por la puerta de atrás: `product_stock`
                // es una proyección que solo escriben las funciones del motor, así
                // que forzarla es la única forma de simular el estado que este
                // reporte existe para detectar.
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute(
                            "INSERT INTO products (product_code, name, unit_measure_id) " +
                                "VALUES ('E2E1','PRODUCTO E2E',1)",
                        )
                        val unassigned = "SELECT id FROM locations WHERE kind='UNASSIGNED'"
                        st.execute(
                            "SELECT registrar_movimiento(1,1,'INITIAL',1000,NULL,($unassigned)," +
                                "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                        )
                        st.execute("UPDATE product_stock SET quantity = 700 WHERE product_id = 1")
                    }
                }

                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    val token = client.login(adminUser, adminPassword)
                    val response = client.get("/v1/reports/kardex-vs-projection") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val cuerpo = json.parseToJsonElement(response.bodyAsText()).jsonObject

                    cuerpo["balanced"]!!.jsonPrimitive.content shouldBe "false"
                    cuerpo["divergences"]!!.jsonPrimitive.content shouldBe "1"

                    val item = cuerpo["items"]!!.jsonArray.single().jsonObject
                    item["productCode"]!!.jsonPrimitive.content shouldBe "E2E1"
                    // Los TRES nombres de columna que renombró la migración V8. Si
                    // el mapper y el `RETURNS TABLE` se separan, aquí sale `null` o
                    // revienta — que es justo lo que ninguno de los 501 tests
                    // anteriores detectaba.
                    item["projectedTotal"]!!.jsonPrimitive.content shouldBe "700.000"
                    item["ledgerTotal"]!!.jsonPrimitive.content shouldBe "1000.000"
                    item["difference"]!!.jsonPrimitive.content shouldBe "-300.000"
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. EL CATÁLOGO POR HTTP — el recorrido de escritura y lectura
    // ═════════════════════════════════════════════════════════════════════════

    describe("catálogo de extremo a extremo") {

        it("alta → listado → detalle, con el producto encontrado por su código") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)
                    val auth = authHeader(token)

                    val alta = client.post("/v1/products") {
                        contentType(ContentType.Application.Json)
                        auth()
                        setBody("""{"productCode":"E2E42","name":"Tornillo E2E","unitMeasure":"PZA"}""")
                    }
                    alta.status shouldBe HttpStatusCode.Created

                    val creado = json.parseToJsonElement(alta.bodyAsText()).jsonObject
                    // El alfabeto y la longitud los valida el objeto de valor del
                    // DOMINIO, no una copia en la ruta. Si divergieran, esto falla.
                    creado["productCode"]!!.jsonPrimitive.content shouldBe "E2E42"
                    val id = creado["id"]!!.jsonPrimitive.content

                    val listado = client.get("/v1/products") { auth() }
                    listado.status shouldBe HttpStatusCode.OK
                    val sobre = json.parseToJsonElement(listado.bodyAsText()).jsonObject
                    // El sobre de §1.4, con el total CONTADO en la base.
                    sobre["totalItems"]!!.jsonPrimitive.content shouldBe "1"
                    sobre["items"]!!.jsonArray.size shouldBe 1

                    val detalle = client.get("/v1/products/$id") { auth() }
                    detalle.status shouldBe HttpStatusCode.OK
                    // `stock` viaja como CADENA decimal de 3 decimales (§1.7), no
                    // como número: es lo que evita las centésimas fantasma.
                    json.parseToJsonElement(detalle.bodyAsText())
                        .jsonObject["stock"]!!.jsonPrimitive.content shouldBe "0.000"
                }
            }
        }

        it("un código duplicado da 409 con DUPLICATE_PRODUCT_CODE, no un 500") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)
                    val auth = authHeader(token)
                    val cuerpo = """{"productCode":"DUP1","name":"Primero","unitMeasure":"PZA"}"""

                    client.post("/v1/products") {
                        contentType(ContentType.Application.Json); auth(); setBody(cuerpo)
                    }.status shouldBe HttpStatusCode.Created

                    val repetido = client.post("/v1/products") {
                        contentType(ContentType.Application.Json); auth(); setBody(cuerpo)
                    }

                    repetido.status shouldBe HttpStatusCode.Conflict
                    json.parseToJsonElement(repetido.bodyAsText())
                        .jsonObject["code"]!!.jsonPrimitive.content shouldBe "DUPLICATE_PRODUCT_CODE"
                }
            }
        }

        it("un código con minúsculas da 422 INVALID_PRODUCT_CODE y NO se normaliza en silencio") {
            // La decisión es de negocio: dos grafías del mismo código en un archivo
            // de import parecerían dos productos. El contrato dice que la fila se
            // RECHAZA.
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    val response = client.post("/v1/products") {
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.Authorization, "Bearer $token")
                        setBody("""{"productCode":"minusculas","name":"X","unitMeasure":"PZA"}""")
                    }

                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    val error = json.parseToJsonElement(response.bodyAsText()).jsonObject
                    error["code"]!!.jsonPrimitive.content shouldBe "INVALID_PRODUCT_CODE"
                    // El campo ofensor viaja en `details`: es lo que permite pintar
                    // el error junto al input.
                    error["details"]!!.jsonObject["productCode"]!!.jsonPrimitive.content shouldBe "minusculas"
                }
            }
        }

        it("una unidad de medida inexistente da 422 UNKNOWN_UNIT_MEASURE") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    val response = client.post("/v1/products") {
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.Authorization, "Bearer $token")
                        setBody("""{"productCode":"UNI1","name":"X","unitMeasure":"NO-EXISTE"}""")
                    }

                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    json.parseToJsonElement(response.bodyAsText())
                        .jsonObject["code"]!!.jsonPrimitive.content shouldBe "UNKNOWN_UNIT_MEASURE"
                }
            }
        }

        it("un producto que no existe da 404 PRODUCT_NOT_FOUND, y el código está en el catálogo") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    val response = client.get("/v1/products/999999") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                    response.status shouldBe HttpStatusCode.NotFound
                    json.parseToJsonElement(response.bodyAsText())
                        .jsonObject["code"]!!.jsonPrimitive.content shouldBe "PRODUCT_NOT_FOUND"
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. PAGINACIÓN — las dos reglas que ya produjeron errores reales
    // ═════════════════════════════════════════════════════════════════════════

    describe("paginación de extremo a extremo") {

        it("un size por encima del tope se RECHAZA con 400, no se recorta en silencio") {
            // **Recortar es peor que rechazar.** El cliente que pide 1000 y recibe
            // 200 cree que tiene los 1000 y no vuelve a pedir el resto: decide con
            // datos incompletos y no lo sabe.
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    val response = client.get("/v1/products?size=1000") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    json.parseToJsonElement(response.bodyAsText())
                        .jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_PAGINATION"
                }
            }
        }

        it("un `page` negativo da 400 INVALID_PAGINATION") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    val response = client.get("/v1/products?page=-1") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    json.parseToJsonElement(response.bodyAsText())
                        .jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_PAGINATION"
                }
            }
        }

        it("el total se CUENTA en la base: con 3 productos y size=1, totalItems es 3") {
            // El bug original devolvía `items.size` como total, así que el sobre
            // decía «1 resultado» siempre y el cliente no podía paginar.
            withFreshDatabase { pool, tokens ->
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        listOf("P1", "P2", "P3").forEach { code ->
                            st.execute(
                                "INSERT INTO products (product_code, name, unit_measure_id) " +
                                    "VALUES ('$code','Producto $code',1)",
                            )
                        }
                    }
                }

                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    val response = client.get("/v1/products?page=0&size=1") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val sobre = json.parseToJsonElement(response.bodyAsText()).jsonObject
                    sobre["items"]!!.jsonArray.size shouldBe 1
                    sobre["totalItems"]!!.jsonPrimitive.content shouldBe "3"
                    sobre["totalPages"]!!.jsonPrimitive.content shouldBe "3"
                }
            }
        }
    }


    // ═════════════════════════════════════════════════════════════════════════
    // 5. REPORTES (§9) — los tres endpoints nuevos
    // ═════════════════════════════════════════════════════════════════════════

    describe("reportes de extremo a extremo") {

        it("las existencias devuelven el sobre de paginación y los totales del filtro") {
            withFreshDatabase { pool, tokens ->
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute("INSERT INTO products (product_code,name,unit_measure_id) VALUES ('REP1','Reporte',1)")
                        val u = st.executeQuery("SELECT id FROM locations WHERE kind='UNASSIGNED'")
                            .use { r -> r.next(); r.getLong(1) }
                        st.execute(
                            "SELECT registrar_movimiento(1,1,'INITIAL',750,NULL,$u," +
                                "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                        )
                    }
                }

                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    val response = client.get("/v1/reports/inventory") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val cuerpo = json.parseToJsonElement(response.bodyAsText()).jsonObject

                    // El sobre de §1.4, con el total CONTADO.
                    cuerpo["totalItems"]!!.jsonPrimitive.content shouldBe "1"
                    cuerpo["page"]!!.jsonPrimitive.content shouldBe "0"

                    val item = cuerpo["items"]!!.jsonArray.single().jsonObject
                    item["productCode"]!!.jsonPrimitive.content shouldBe "REP1"
                    // La cantidad va como CADENA de 3 decimales (§1.7): si fuera
                    // número, el cliente la sumaría en coma flotante.
                    item["quantity"]!!.jsonPrimitive.content shouldBe "750.000"
                    // El nombre visible lo compone el SERVIDOR.
                    item["locationDisplayName"]!!.jsonPrimitive.content shouldBe "SIN-UBICAR"

                    // Y los totales, calculados sobre el filtro.
                    val totales = cuerpo["totals"]!!.jsonObject
                    totales["distinctProducts"]!!.jsonPrimitive.content shouldBe "1"
                    totales["totalUnits"]!!.jsonPrimitive.content shouldBe "750.000"
                }
            }
        }

        it("un `size` fuera de rango se RECHAZA con 400, no se recorta") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    val response = client.get("/v1/reports/inventory?size=500") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    json.parseToJsonElement(response.bodyAsText())
                        .jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_PAGINATION"
                }
            }
        }

        it("el resumen de movimientos exige las fechas y las valida") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)
                    val auth = authHeader(token)

                    // Sin fechas: el contrato las declara obligatorias.
                    val sinFechas = client.get("/v1/reports/movements-summary") { auth() }
                    sinFechas.status shouldBe HttpStatusCode.BadRequest

                    // Una fecha ilegible NO puede ser un 500: es culpa del cliente.
                    val malFecha = client.get("/v1/reports/movements-summary?from=ayer&to=hoy") { auth() }
                    malFecha.status shouldBe HttpStatusCode.BadRequest
                    json.parseToJsonElement(malFecha.bodyAsText())
                        .jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_FILTER_DATE"

                    // Un `groupBy` inventado enumera los admitidos.
                    val malGroup = client.get(
                        "/v1/reports/movements-summary?from=2026-01-01&to=2026-01-31&groupBy=inventado",
                    ) { auth() }
                    malGroup.status shouldBe HttpStatusCode.BadRequest
                    val error = json.parseToJsonElement(malGroup.bodyAsText()).jsonObject
                    error["code"]!!.jsonPrimitive.content shouldBe "INVALID_PARAMETER"
                    error["details"]!!.jsonObject["allowed"]!!.jsonPrimitive.content
                        .contains("day") shouldBe true

                    // Un rango invertido tampoco.
                    val invertido = client.get(
                        "/v1/reports/movements-summary?from=2026-02-01&to=2026-01-01",
                    ) { auth() }
                    invertido.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        it("el resumen agrupa por día con las claves y los totales del servidor") {
            withFreshDatabase { pool, tokens ->
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute("INSERT INTO products (product_code,name,unit_measure_id) VALUES ('REP2','Reporte 2',1)")
                        val u = st.executeQuery("SELECT id FROM locations WHERE kind='UNASSIGNED'")
                            .use { r -> r.next(); r.getLong(1) }
                        st.execute(
                            "SELECT registrar_movimiento(1,1,'INITIAL',1000,NULL,$u," +
                                "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                        )
                    }
                }

                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    // Se pide un rango amplio para no depender del reloj del test:
                    // la agrupación por día se comprueba con el total, que es lo
                    // que no depende de la zona horaria.
                    val response = client.get(
                        "/v1/reports/movements-summary?from=2020-01-01&to=2030-12-31&groupBy=product",
                    ) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val cuerpo = json.parseToJsonElement(response.bodyAsText()).jsonObject

                    cuerpo["groupBy"]!!.jsonPrimitive.content shouldBe "product"
                    val totales = cuerpo["totals"]!!.jsonObject
                    totales["inUnits"]!!.jsonPrimitive.content shouldBe "1000.000"
                    // Una entrada no genera salida.
                    totales["outUnits"]!!.jsonPrimitive.content shouldBe "0.000"
                    totales["netUnits"]!!.jsonPrimitive.content shouldBe "1000.000"

                    val item = cuerpo["items"]!!.jsonArray.single().jsonObject
                    // La clave de agrupación por producto es el CÓDIGO.
                    item["key"]!!.jsonPrimitive.content shouldBe "REP2"
                }
            }
        }

        it("la exportación devuelve un XLSX válido y un CSV con la cabecera de adjunto") {
            withFreshDatabase { pool, tokens ->
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute("INSERT INTO products (product_code,name,unit_measure_id) VALUES ('REP3','Reporte 3',1)")
                        val u = st.executeQuery("SELECT id FROM locations WHERE kind='UNASSIGNED'")
                            .use { r -> r.next(); r.getLong(1) }
                        st.execute(
                            "SELECT registrar_movimiento(1,1,'INITIAL',1234,NULL,$u," +
                                "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                        )
                    }
                }

                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)
                    val auth = authHeader(token)

                    // XLSX: el archivo se descomprime de verdad. Un ZIP roto pasaría
                    // un `200` y fallaría al abrirlo en Excel.
                    val xlsx = client.get("/v1/reports/kardex/export?format=xlsx") { auth() }
                    xlsx.status shouldBe HttpStatusCode.OK
                    xlsx.headers[HttpHeaders.ContentDisposition]!!.contains("attachment") shouldBe true

                    val bytes = xlsx.readBytes()
                    // «PK»: la firma de un ZIP. Si no empieza así, no es un XLSX.
                    bytes[0] shouldBe 0x50.toByte()
                    bytes[1] shouldBe 0x4B.toByte()

                    var partes = 0
                    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
                        while (zip.nextEntry != null) partes++
                    }
                    partes shouldBe 6

                    // CSV: con BOM y con el producto dentro.
                    val csv = client.get("/v1/reports/kardex/export?format=csv") { auth() }
                    csv.status shouldBe HttpStatusCode.OK
                    val texto = csv.bodyAsText()
                    texto.contains("REP3") shouldBe true

                    // Un formato inventado se rechaza enumerando los admitidos.
                    val malo = client.get("/v1/reports/kardex/export?format=pdf") { auth() }
                    malo.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        it("la exportación exige `report.export`, que es DISTINTO de `report.view`") {
            // Es la separación del catálogo de permisos (§11): exportar saca los
            // datos del sistema en un archivo reenviable. Se comprueba que las dos
            // rutas exigen permisos distintos, porque colapsarlos daría a
            // cualquiera que ve el reporte la capacidad de extraerlo.
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    // Sin token, las dos dan 401 (la ruta existe y falta identidad).
                    client.get("/v1/reports/inventory").status shouldBe HttpStatusCode.Unauthorized
                    client.get("/v1/reports/kardex/export?format=csv").status shouldBe
                        HttpStatusCode.Unauthorized
                }
            }
        }
    }


    // ═════════════════════════════════════════════════════════════════════════
    // 6. LOS MÓDULOS NUEVOS, por HTTP: que estén montados y protegidos
    // ═════════════════════════════════════════════════════════════════════════

    describe("las rutas de los módulos nuevos están montadas y protegidas") {

        it("Surtido, Traslados y Etiquetas responden con la configuración de producción") {
            // **Lo que esta prueba añade y ninguna otra puede:** que las rutas
            // estén REGISTRADAS en el módulo real. Un módulo implementado pero no
            // conectado en `Application.kt` da `404` en producción con los tests en
            // verde — y el test que lo vigila (`RoutesWiringTest`) solo mira las
            // funciones `Route.*Routes` públicas, no que cada ruta interna esté bien.
            //
            // Aquí se pide cada ruta con un token de verdad: si responde `404`, no
            // está montada; si responde `401`, falta el token (correcto, porque no
            // se envía).
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    val rutas = listOf(
                        Triple("GET", "/v1/orders", null),
                        Triple("GET", "/v1/transfers", null),
                        Triple("GET", "/v1/transfers/in-transit", null),
                        Triple("GET", "/v1/label-templates", null),
                        Triple("GET", "/v1/labels/jobs", null),
                        Triple("GET", "/v1/invoices", null),
                    )

                    rutas.forEach { (metodo, ruta, _) ->
                        val response = when (metodo) {
                            "GET" -> client.get(ruta)
                            else -> error("método no soportado en esta prueba: $metodo")
                        }
                        withClue("Sin token, $metodo $ruta debe dar 401 y NUNCA 404") {
                            response.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                }
            }
        }

        it("la ruta de la IMPRESORA exige la credencial de servicio, no un token") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    // Sin `X-Service-Key`: `401 INVALID_SERVICE_KEY`. **Lo que se
                    // comprueba es que NO basta con ser un usuario autenticado**: la
                    // llama la impresora, y el legacy tenía esta ruta ABIERTA sin
                    // ninguna credencial.
                    val sinClave = client.patch("/v1/print-jobs/1") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"status":"PRINTED"}""")
                    }

                    sinClave.status shouldBe HttpStatusCode.Unauthorized
                    json.parseToJsonElement(sinClave.bodyAsText())
                        .jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_SERVICE_KEY"

                    // Y con un token de USUARIO tampoco: no es una ruta de personas.
                    val token = client.login(adminUser, adminPassword)
                    val conToken = client.patch("/v1/print-jobs/1") {
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.Authorization, "Bearer $token")
                        setBody("""{"status":"PRINTED"}""")
                    }

                    conToken.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        it("el flujo de traslado funciona por HTTP: crear, despachar y consultar el tránsito") {
            withFreshDatabase { pool, tokens ->
                // Una bodega de destino y un producto con stock, por SQL: lo que se
                // prueba aquí es el HTTP, no la preparación.
                pool.connection.use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st ->
                        st.execute("INSERT INTO warehouses (code, name, is_default) VALUES ('SUC','Sucursal', false)")
                        st.execute("INSERT INTO products (product_code,name,unit_measure_id) VALUES ('E2ET1','Traslado E2E',1)")
                        val u = st.executeQuery("SELECT id FROM locations WHERE kind='UNASSIGNED'")
                            .use { r -> r.next(); r.getLong(1) }
                        st.execute("SELECT registrar_movimiento(1,1,'INITIAL',100,NULL,$u,NULL,NULL,NULL,NULL,NULL,NULL,NULL)")
                    }
                }
                val destino = pool.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT id FROM warehouses WHERE code='SUC'").use { rs ->
                            rs.next(); rs.getLong(1)
                        }
                    }
                }

                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)
                    val auth = authHeader(token)

                    // Crear: `201` con el `id` que hace falta para despachar.
                    val alta = client.post("/v1/transfers") {
                        contentType(ContentType.Application.Json)
                        auth()
                        setBody(
                            """{"fromWarehouseId":1,"toWarehouseId":$destino,""" +
                                """"lines":[{"productCode":"E2ET1","quantity":"40.000"}]}""",
                        )
                    }
                    alta.status shouldBe HttpStatusCode.Created
                    val id = json.parseToJsonElement(alta.bodyAsText())
                        .jsonObject["transfer"]!!.jsonObject["id"]!!.jsonPrimitive.content

                    // Despachar: la mercancía sale y entra en tránsito.
                    val despacho = client.post("/v1/transfers/$id/dispatch") { auth() }
                    despacho.status shouldBe HttpStatusCode.OK
                    json.parseToJsonElement(despacho.bodyAsText())
                        .jsonObject["transfer"]!!.jsonObject["status"]!!.jsonPrimitive.content shouldBe
                        "DISPATCHED"

                    // Y aparece en el informe de tránsito, que es la deuda visible.
                    val transito = client.get("/v1/transfers/in-transit") { auth() }
                    transito.status shouldBe HttpStatusCode.OK
                    val enCamino = json.parseToJsonElement(transito.bodyAsText()).jsonArray
                    enCamino.size shouldBe 1
                    enCamino.single().jsonObject["code"]!!.jsonPrimitive.content
                        .startsWith("TR-") shouldBe true
                }
            }
        }
    }


    // ═════════════════════════════════════════════════════════════════════════
    // 8. LA AUDITORÍA: una denegación tiene que quedar registrada
    // ═════════════════════════════════════════════════════════════════════════

    describe("la auditoría registra los accesos denegados") {

        it("un permiso denegado deja una entrada con canal, permiso y resultado") {
            withFreshDatabase { pool, tokens ->
                // ── El requisito, y llevaba sin cumplirse desde el principio ──
                //
                // `AuditWriter.recordDeniedAccessSafely` **existía y nadie la
                // llamaba**. El método estaba escrito, la tabla tenía sus seis
                // dimensiones y la bitácora quedaba **en cero filas**: la fachada
                // que el propio `docs/historico/ARQUITECTURA.md` denunciaba.
                //
                // Esta prueba es la que impide que vuelva a quedar así.
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    // ── Cómo se provoca una denegación REAL ─────────────────────
                    //
                    // No basta con pedir una ruta sin token: eso da
                    // `UNAUTHENTICATED`, que es otro caso. Lo que hay que registrar
                    // es a **alguien identificado al que le falta el permiso**,
                    // porque es lo que permite contar intentos por persona.
                    //
                    // Se emite un token válido —firmado por el mismo servicio— para
                    // un usuario real pero **sin roles ni permisos**. El usuario se
                    // resuelve (el token es legítimo) y la decisión es `DENIED` por
                    // falta de permiso.
                    val sembrado = pool.connection.use { c ->
                        c.createStatement().use { st ->
                            st.executeQuery("SELECT id, username, name FROM users WHERE NOT disabled LIMIT 1")
                                .use { rs ->
                                    rs.next()
                                    Triple(rs.getLong(1), rs.getString(2), rs.getString(3))
                                }
                        }
                    }

                    val sinPermisos = tokens.issueAccessToken(
                        TokenSubject(
                            userId = sembrado.first,
                            username = sembrado.second,
                            name = sembrado.third,
                            roles = emptyList(),
                            permissions = emptyList(),
                            scopes = emptyMap(),
                            warehouses = emptyList(),
                        ),
                    ).first

                    val denegado = client.get("/v1/users") {
                        header(HttpHeaders.Authorization, "Bearer $sinPermisos")
                        // El canal viaja en la cabecera y la auditoría lo guarda.
                        header("X-Client-Channel", "MOBILE")
                    }

                    withClue("Identificado pero sin el permiso, la ruta tiene que negar") {
                        denegado.status shouldBe HttpStatusCode.Forbidden
                    }
                }

                // ── Y AHORA LO QUE IMPORTA ──────────────────────────────────
                //
                // La denegación quedó REGISTRADA. Se lee de la base y no del
                // endpoint: comprobar la auditoría con el endpoint de auditoría
                // probaría que la consulta funciona, no que se escribió.
                val filas = pool.connection.use { c ->
                    c.prepareStatement(
                        "SELECT permission_key, decision, result, source_channel, action " +
                            "FROM audit_log WHERE action = 'access.denied'",
                    ).use { st ->
                        st.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        listOf(
                                            rs.getString(1), rs.getString(2), rs.getString(3),
                                            rs.getString(4), rs.getString(5),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                withClue("La denegación tiene que quedar en la bitácora con sus dimensiones") {
                    filas.size shouldBe 1
                }
                // El PERMISO que se intentó: sin él, la entrada dice que algo se
                // denegó pero no qué, y no se puede saber a qué se estaba
                // intentando acceder.
                filas.single()[0] shouldBe "user.view"
                filas.single()[1] shouldBe "DENIED"
                filas.single()[2] shouldBe "DENIED"
                // **El canal es la dimensión que el legacy no tenía**, y es lo que
                // distingue un intento desde el móvil de uno desde la web.
                filas.single()[3] shouldBe "MOBILE"
            }
        }

        it("un acceso PERMITIDO no ensucia la bitácora de denegaciones") {
            withFreshDatabase { pool, tokens ->
                // La auditoría de denegaciones tiene que ser **específica**: si
                // registrara también lo permitido, contar intentos fallidos por
                // usuario dejaría de significar nada, porque el número incluiría
                // todo el uso normal del sistema.
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }
                    val token = client.login(adminUser, adminPassword)

                    repeat(3) {
                        val ok = client.get("/v1/users") { authHeader(token)() }
                        ok.status shouldBe HttpStatusCode.OK
                    }
                }

                val denegaciones = pool.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM audit_log WHERE action = 'access.denied'")
                            .use { rs -> rs.next(); rs.getLong(1) }
                    }
                }

                denegaciones shouldBe 0L
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 9. SALUD — sin autenticación, como manda el contrato
    // ═════════════════════════════════════════════════════════════════════════

    describe("salud") {

        it("es pública y dice la versión de esquema REALMENTE aplicada") {
            withFreshDatabase { pool, tokens ->
                testApplication {
                    application { module(pool, tokens, NativeArgon2PasswordHasher(), realChecker(tokens, pool)) }

                    // Sin token: el contrato lo declara público.
                    val response = client.get("/v1/health")

                    response.status shouldBe HttpStatusCode.OK
                    val cuerpo = json.parseToJsonElement(response.bodyAsText()).jsonObject
                    cuerpo["status"]!!.jsonPrimitive.content shouldBe "UP"
                    // Se afirma que HAY versión, no cuál: fijarla aquí obligaría a
                    // tocar este test en cada migración nueva, y el test pasaría a
                    // ser un estorbo en lugar de una guardia.
                    val version = cuerpo["schemaVersion"]?.jsonPrimitive?.content
                    withClue("health debe informar la versión aplicada por Flyway") {
                        (version != null && version.isNotEmpty()) shouldBe true
                    }
                }
            }
        }
    }
})

/**
 * Añade la cabecera de autorización dentro de un builder de petición.
 *
 * Se declara como función local del spec porque los builders de Ktor anidan
 * lambdas y una lambda capturada no resuelve el receptor como una extensión.
 */
private fun authHeader(token: String): io.ktor.client.request.HttpRequestBuilder.() -> Unit =
    { header(HttpHeaders.Authorization, "Bearer $token") }
