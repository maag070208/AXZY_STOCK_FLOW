package dev.axzy.stock.backend.docs

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * VERIFICACIÓN DE LA DOCUMENTACIÓN OpenAPI
 *
 * Monta el módulo de documentación en una aplicación de prueba —sin base de
 * datos, sin puerto y sin tocar `Application.kt`— y comprueba tres cosas que no
 * se pueden comprobar compilando:
 *
 *  1. Que `GET /openapi.json` responde 200 con un documento OpenAPI 3.0.3
 *     parseable, con sus esquemas de seguridad y sus 27 rutas.
 *  2. Que `GET /docs` sirve Swagger UI.
 *  3. Que `GET /openapi` renderiza la documentación HTML, lo que de paso hace
 *     que **el parser de Swagger valide el documento entero**: si un `$ref`
 *     apuntara a un esquema inexistente, esta comprobación falla.
 *
 * El test es el único sitio donde se puede verificar esto mientras
 * `Application.kt` no llame a `installOpenApiDocumentation()`.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class OpenApiDocumentationTest {

    /**
     * Nombre del esquema de seguridad, escrito aquí y no importado del código
     * de producción A PROPÓSITO: así el test comprueba el contrato publicado
     * (`bearerAuth`, el que verá un cliente) y no una constante interna.
     */
    private val bearerScheme = "bearerAuth"

    /**
     * Rutas que el backend expone HOY.
     *
     * **ESTA LISTA ES MANUAL Y ESO ES UN RIESGO CONOCIDO**: el test comprueba que
     * el documento y el servidor digan lo mismo, pero la lista que usa como
     * verdad intermedia alguien tiene que mantenerla. Se descubrió al añadir los
     * reportes: el test falló —correctamente— porque el documento iba por delante.
     *
     * Se deja manual a propósito mientras siga habiendo pocas rutas: leer las
     * rutas del árbol con un regex es lo que **ya falló** en otro test de este
     * proyecto (confundía `get {` con `get(` y no veía las rutas anidadas en
     * `route(...)`). Una lista explícita que falla cuando no coincide es más
     * honesta que un descubrimiento automático que acierta a veces.
     */
    private val realRoutes = setOf(
        "/v1/auth/login", "/v1/auth/refresh", "/v1/auth/logout", "/v1/auth/me",
        "/v1/auth/change-password",
        "/v1/products", "/v1/products/import", "/v1/products/{id}",
        "/v1/locations", "/v1/locations/{id}",
        "/v1/warehouses",
        "/v1/inventory/stock", "/v1/inventory/kardex", "/v1/inventory/adjustments",
        "/v1/inventory/locate", "/v1/inventory/move",
        "/v1/invoices", "/v1/invoices/{id}", "/v1/invoices/pending-approval",
        "/v1/invoices/{id}/close",
        "/v1/invoices/{id}/lines/{lineId}/capture", "/v1/invoices/{id}/approve",
        "/v1/users", "/v1/roles", "/v1/permissions",
        "/v1/invoices/{id}/reject-discrepancy",
        "/v1/label-templates", "/v1/label-templates/{id}",
        "/v1/labels/preview", "/v1/labels/print",
        "/v1/labels/jobs", "/v1/labels/jobs/{id}", "/v1/labels/jobs/{id}/reprint",
        "/v1/print-jobs/{id}",
        "/v1/orders", "/v1/orders/{id}", "/v1/orders/{id}/lines/{lineId}/package",
        "/v1/orders/{id}/confirm", "/v1/orders/{id}/export", "/v1/orders/{id}/shortage",
        "/v1/transfers", "/v1/transfers/{id}", "/v1/transfers/in-transit",
        "/v1/transfers/{id}/dispatch", "/v1/transfers/{id}/receive", "/v1/transfers/{id}/cancel",
        "/v1/audit/log",
        "/v1/reports/inventory", "/v1/reports/movements-summary", "/v1/reports/kardex/export",
        "/v1/reports/kardex-vs-projection",
        "/v1/health",
    )

    /** Operaciones declaradas en `OpenApiDocumentation.kt`. */
    private val declaredOperations = 63

    @Test
    fun `el document OpenAPI se sirve y declara la API y su seguridad`() = testApplication {
        application { installOpenApiDocumentation() }

        val response = client.get(OPENAPI_JSON_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        val type = response.headers["Content-Type"].orEmpty()
        assertTrue(
            type.startsWith("application/json"),
            "El JSON de OpenAPI debe servirse como application/json, no como '$type'.",
        )

        val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("3.0.3", document.text("openapi"))

        val info = assertNotNull(document["info"], "Falta `info`.").jsonObject
        assertEquals("1.0.0", info.text("version"))
        assertTrue(info.text("title").contains("Almacén"), "El título debe nombrar la API.")
        assertTrue(
            info.text("description").contains("INVALID_PAGINATION"),
            "La descripción debe explicar el contrato de error y su catálogo cerrado.",
        )

        // ── El esquema de seguridad Bearer/JWT ─────────────────────────────
        val security = document["components"]!!.jsonObject["securitySchemes"]!!.jsonObject
        val bearer = assertNotNull(security[bearerScheme], "Falta el esquema Bearer.").jsonObject
        assertEquals("http", bearer.text("type"))
        assertEquals("bearer", bearer.text("scheme"))
        assertEquals("JWT", bearer.text("bearerFormat"))

        // La credencial de servicio va en su propia cabecera, no en Authorization.
        val service = assertNotNull(security["serviceKey"], "Falta el esquema X-Service-Key.").jsonObject
        assertEquals("apiKey", service.text("type"))
        assertEquals("X-Service-Key", service.text("name"))
    }

    @Test
    fun `estan documentadas todas las rutas reales y cada operation con su response`() = testApplication {
        application { installOpenApiDocumentation() }

        val document = Json.parseToJsonElement(client.get(OPENAPI_JSON_PATH).bodyAsText()).jsonObject
        val routes = document["paths"]!!.jsonObject

        val missing = realRoutes - routes.keys
        assertTrue(missing.isEmpty(), "Rutas reales sin documentar: $missing")

        val extraRoutes = routes.keys - realRoutes
        assertTrue(
            extraRoutes.isEmpty(),
            "El documento describe rutas que NO existen en el backend: $extraRoutes. " +
                "La documentación promete lo que el servidor no hace.",
        )
        assertEquals(realRoutes.size, routes.size, "El conjunto de rutas no coincide.")

        val operations = routes.values.sumOf { it.jsonObject.size }
        assertEquals(
            declaredOperations,
            operations,
            "Cambió el número de operaciones documentadas. Si es una ruta nueva, hay que " +
                "declararla aquí y en OpenApiDocumentation.kt.",
        )

        routes.forEach { (route, byMethod) ->
            byMethod.jsonObject.forEach { (method, operation) ->
                val op = operation.jsonObject
                assertNotNull(op["summary"], "$method $route sin `summary`.")
                assertNotNull(op["operationId"], "$method $route sin `operationId`.")

                val responses = assertNotNull(op["responses"], "$method $route sin respuestas.").jsonObject
                assertTrue(
                    responses.keys.any { it.startsWith("2") || it == "503" },
                    "$method $route no declara ninguna respuesta de éxito (ni 503 en salud).",
                )

                // Todo lo que no es público exige Bearer, y lo dice. El grupo de
                // autenticación es el único que no declara permiso: solo identidad.
                val isPublic = route in setOf("/v1/auth/login", "/v1/auth/refresh", "/v1/auth/logout") ||
                    route == "/v1/health"
                val identityOnly = route.startsWith("/v1/auth/")
                val requiresBearer = op["security"]?.jsonArray
                    ?.firstOrNull()?.jsonObject?.containsKey(bearerScheme) == true

                // ── La excepción: la ruta de la IMPRESORA ───────────────────────
                //
                // `PATCH /v1/print-jobs/{id}` la llama la Zebra con
                // `X-Service-Key`, **no con un JWT de usuario**: no hay persona
                // detrás. Exigirle Bearer sería exigirle una credencial que no
                // tiene, y el contrato lo separa a propósito para que en los
                // registros se distinga «lo hizo la impresora» de «lo hizo
                // alguien».
                //
                // **`X-Service-Key` y no `Authorization`**, y por eso el esquema es
                // otro. El legacy tenía esta ruta ABIERTA sin credencial.
                val isServiceKey = op["security"]?.jsonArray
                    ?.firstOrNull()?.jsonObject?.containsKey("serviceKey") == true

                if (isPublic) {
                    assertFalse(requiresBearer, "$method $route es público y declara seguridad.")
                } else if (route == "/v1/print-jobs/{id}") {
                    assertTrue(
                        isServiceKey,
                        "$method $route tiene que declarar la credencial de servicio (serviceKey), " +
                            "que es la que usa la impresora.",
                    )
                } else {
                    assertTrue(requiresBearer, "$method $route no declara la seguridad Bearer.")
                }
                if (isPublic || identityOnly || isServiceKey) {
                    // **La ruta de la impresora no lleva permiso y no es un
                    // descuido:** la autoriza la CREDENCIAL DE SERVICIO, no un rol
                    // de usuario. Exigirle un `x-required-permission` obligaría a
                    // inventar un permiso que ninguna persona tiene, y el `403`
                    // resultante culparía al rol equivocado.
                    assertFalse(
                        op.containsKey("x-required-permission"),
                        "$method $route se autoriza con credencial de servicio y no debe " +
                            "declarar un permiso de usuario.",
                    )
                } else {
                    assertNotNull(
                        op["x-required-permission"],
                        "$method $route no declara el permiso que exige.",
                    )
                }
            }
        }
    }

    @Test
    fun `la reconciliacion documenta los fields reales y no los que prometia el contrato`() = testApplication {
        application { installOpenApiDocumentation() }

        val document = Json.parseToJsonElement(client.get(OPENAPI_JSON_PATH).bodyAsText()).jsonObject
        val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
        val properties = schemas["ReconciliationReport"]!!.jsonObject["properties"]!!.jsonObject

        // `CONTRATO_API.md` §9 prometía estos tres y la ruta NUNCA los devolvió. El
        // contrato ya se corrigió, así que esta comprobación ya no vigila una mentira
        // viva: **vigila que no vuelva**. Si alguien reintroduce los nombres del
        // contrato viejo, el esquema publicado dejaría de coincidir con el DTO.
        assertFalse(properties.containsKey("productsChecked"), "§9: `productsChecked` no existe en la ruta.")
        assertFalse(properties.containsKey("ledgerNet"), "§9: `ledgerNet` no existe en la ruta.")
        assertFalse(properties.containsKey("projected"), "§9: `projected` no existe en la ruta.")

        // Lo que la ruta devuelve de verdad. El booleano se llamaba `cuadra` hasta la
        // migración V8: era un campo de API pública con identificador en español, y
        // `totalEnBd`/`totalEsperado`/`diferencia` llegaban así al JSON porque son las
        // columnas del `RETURNS TABLE` de `comprobar_proyecciones()`.
        assertTrue(properties.containsKey("balanced"), "Falta `balanced`, que es lo que devuelve la ruta.")
        assertTrue(properties.containsKey("divergences"))
        assertTrue(properties.containsKey("checkedAt"))
        assertFalse(properties.containsKey("cuadra"), "`cuadra` es el nombre VIEJO: no debe volver.")

        val divergence = schemas["DivergenceDto"]!!.jsonObject["properties"]!!.jsonObject
        assertTrue(divergence.containsKey("projectedTotal"), "Falta `projectedTotal`.")
        assertTrue(divergence.containsKey("ledgerTotal"))
        assertTrue(divergence.containsKey("difference"))

        // Y las divergencias viajan en el propio documento.
        val divergences = assertNotNull(document["x-contract-divergences"]).jsonArray
        assertTrue(divergences.size >= 10, "El documento debe publicar los desajustes encontrados.")
    }

    @Test
    fun `el catalogo cerrado de codigos de error esta en el esquema`() = testApplication {
        application { installOpenApiDocumentation() }

        val document = Json.parseToJsonElement(client.get(OPENAPI_JSON_PATH).bodyAsText()).jsonObject
        val error = document["components"]!!.jsonObject["schemas"]!!.jsonObject["ErrorResponse"]!!.jsonObject
        val codes = error["properties"]!!.jsonObject["code"]!!.jsonObject["enum"]!!
            .jsonArray.map { it.jsonPrimitive.content }.toSet()

        // Los cuatro que el enunciado nombra, y los del contrato.
        assertTrue(codes.containsAll(listOf("INSUFFICIENT_STOCK", "REASON_REQUIRED", "SEGREGATION_OF_DUTIES", "INVALID_PAGINATION")))
        // Los cinco de autorización, que el cliente DEBE distinguir.
        assertTrue(codes.containsAll(listOf("UNAUTHENTICATED", "PERMISSION_DENIED", "SCOPE_INSUFFICIENT", "POLICY_DENIED")))
        // Los que el código emite y el contrato NO lista: se documentan igual.
        assertTrue(codes.containsAll(listOf("PRODUCT_NOT_FOUND", "INVALID_LOCATION_CODE")))
        // Lo que emite ReceivingService cuando nada está pendiente (coincide con el contrato §5).
        assertTrue(codes.contains("NO_PENDING_APPROVAL"))
        assertTrue(codes.contains("NOT_FOUND") && codes.contains("INTERNAL_ERROR"))
    }

    @Test
    fun `Swagger UI se sirve en docs y la documentacion HTML en openapi`() = testApplication {
        application { installOpenApiDocumentation() }

        val ui = client.get(SWAGGER_UI_PATH)
        assertEquals(HttpStatusCode.OK, ui.status, "Swagger UI debe responder 200 en $SWAGGER_UI_PATH.")
        assertTrue(
            ui.bodyAsText().contains("swagger-ui"),
            "La página de $SWAGGER_UI_PATH no parece Swagger UI.",
        )

        // Renderizar el HTML obliga al parser de Swagger a validar el documento
        // entero: un `$ref` roto lo hace fallar.
        val html = client.get(OPENAPI_HTML_PATH)
        assertEquals(
            HttpStatusCode.OK,
            html.status,
            "La documentación HTML de $OPENAPI_HTML_PATH no se pudo renderizar.",
        )
    }
}

private fun JsonObject.text(key: String): String =
    assertNotNull(this[key], "Falta la clave '$key'.").jsonPrimitive.content
