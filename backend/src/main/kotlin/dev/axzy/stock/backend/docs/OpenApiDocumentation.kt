package dev.axzy.stock.backend.docs

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.plugins.openapi.openAPI
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DOCUMENTACIÓN DE LA API — OpenAPI 3.0.3 + Swagger UI
 *
 * `PLAN_ALMACEN_INTEGRAL.md` §0 declara «OpenAPI generado desde el código» como
 * estándar del proyecto. Esto es su implementación.
 *
 * ── POR QUÉ EL DOCUMENTO SE CONSTRUYE AQUÍ Y NO SE ANOTA RUTA A RUTA ───────
 *
 * Hay dos familias de herramientas para documentar una API de Ktor:
 *
 *  1. **Las oficiales** (`ktor-server-openapi` + `ktor-server-swagger`). En Ktor
 *     3.1.1 —la versión de este proyecto— sirven un documento que YA EXISTE (un
 *     recurso del jar o un archivo). La generación a partir del árbol de rutas
 *     (`OpenApiDocSource.Routing`, el DSL `.describe {}` y el plugin de
 *     compilador del Ktor Gradle plugin) llegó en Ktor 3.2/3.3, y ese plugin de
 *     compilador exige Kotlin 2.2.20 (aquí hay 2.4.10). Comprobado con `javap`
 *     sobre el jar real de 3.1.1: solo expone `OpenAPIConfig` y
 *     `openAPI(path, swaggerFile)`. Subir todo Ktor está fuera del alcance.
 *
 *  2. **`io.github.smiley4:ktor-openapi`**, que documenta con un bloque
 *     `openApi { }` DENTRO de cada manejador. Obligaría a tocar los 7 archivos
 *     `*Routes.kt`.
 *
 * Con la restricción de no modificar los `.kt` existentes (otro agente está
 * renombrando identificadores en `backend/` y `stock-domain/` en paralelo), la
 * única vía honesta es **declarar el documento en este archivo**, en Kotlin, y
 * servirlo. Ventaja lateral que conviene no perder: este archivo **no importa
 * nada del backend ni del dominio** (solo Ktor y kotlinx.serialization), así que
 * un renombrado masivo de identificadores no lo rompe.
 *
 * El precio, y hay que decirlo: **el documento no se deduce del código de las
 * rutas**, se mantiene a mano al lado. Si una ruta cambia, hay que cambiar
 * también su declaración aquí. Lo que sí está garantizado por el compilador es
 * que el documento es JSON válido y que los esquemas son coherentes entre sí.
 *
 * ── FIDELIDAD ──────────────────────────────────────────────────────────────
 *
 * Los campos de cada respuesta son los que **el código devuelve hoy**, leídos de
 * los DTO de `backend/`, no los que promete `CONTRATO_API.md`. Las divergencias
 * encontradas están listadas en [DIVERGENCIAS_CONTRATO] y viajan en el propio
 * documento como extensión `x-contract-divergences`, para que el desajuste no se
 * pierda.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Ruta del JSON de OpenAPI (lo que consumen generadores de cliente y herramientas). */
const val OPENAPI_JSON_PATH: String = "/openapi.json"

/** Ruta de Swagger UI (interactivo, en el navegador). */
const val SWAGGER_UI_PATH: String = "/docs"

/** Ruta de la documentación HTML que renderiza Ktor en el servidor (sin CDN). */
const val OPENAPI_HTML_PATH: String = "/openapi"

private const val API_TITLE = "Almacén Integral — API"
private const val API_VERSION = "1.0.0"
private const val BEARER_SCHEME = "bearerAuth"
private const val SERVICE_KEY_SCHEME = "serviceKey"

/**
 * Conecta la documentación de la API.
 *
 * **La línea que hay que añadir a `Application.kt` es `installOpenApiDocumentation()`,
 * como última sentencia de `fun Application.module(...)`, justo DESPUÉS del
 * bloque `routing { ... }` que ya existe** (no dentro: esta función abre su
 * propio `routing {}`, y Ktor admite abrirlo varias veces sobre el mismo árbol).
 *
 * Servido:
 *  - `GET /openapi.json` — el documento OpenAPI 3.0.3 en JSON.
 *  - `GET /docs`         — Swagger UI apuntando a ese JSON.
 *  - `GET /openapi`      — documentación HTML renderizada por Ktor.
 *
 * Es **público y sin JWT**, como `/v1/health`: es documentación, no datos. Los
 * endpoints documentados siguen exigiendo su token y su permiso; la
 * documentación no relaja nada.
 */
fun Application.installOpenApiDocumentation(
    jsonPath: String = OPENAPI_JSON_PATH,
    swaggerPath: String = SWAGGER_UI_PATH,
    htmlPath: String = OPENAPI_HTML_PATH,
) {
    val serializado = OUTPUT_JSON
        .encodeToString(JsonElement.serializer(), openApiDocument())

    // Los plugins de Ktor 3.1.1 solo aceptan un archivo (o un recurso del jar),
    // no un documento en memoria. Se materializa UNA vez al arrancar. Si el
    // sistema de archivos no lo permite, se degrada: el JSON —lo que de verdad
    // consumen los clientes— se sigue sirviendo, y las dos UIs no.
    val documentFile: File? = runCatching { materializar(serializado) }
        .getOrElse { cause ->
            environment.log.warn(
                "No se pudo escribir el documento OpenAPI en un archivo temporal; " +
                    "se sirve $jsonPath pero no Swagger UI ni $htmlPath.",
                cause,
            )
            null
        }

    routing {
        get(jsonPath) {
            call.respondText(serializado, ContentType.Application.Json)
        }

        if (documentFile != null) {
            // Swagger UI: se sirve en el navegador y se apoya en el CDN de
            // swagger-ui-dist (así funciona el plugin en Ktor 3.1.1). Necesita
            // salida a internet; el JSON de al lado no.
            swaggerUI(swaggerPath, documentFile) { deepLinking = true }

            // La documentación HTML de Ktor: no usa CDN, funciona sin red.
            openAPI(htmlPath, documentFile.absolutePath)
        }
    }
}

/**
 * El formato de salida del documento.
 *
 * Una sola instancia: crear un `Json` por uso es lento y lo señala el
 * compilador. `prettyPrint` porque este JSON se lee a mano en `/openapi.json`
 * (y un `diff` legible es la mitad del valor de tenerlo en el repositorio).
 */
private val OUTPUT_JSON: Json = Json { prettyPrint = true }

/** Escribe el documento en un temporal de vida igual a la del proceso. */
private fun materializar(json: String): File =    File.createTempFile("stock-openapi-", ".json").apply {
        writeText(json)
        deleteOnExit()
    }

// ═════════════════════════════════════════════════════════════════════════════
// EL DOCUMENTO
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Construye el documento OpenAPI completo.
 *
 * `internal` y no `private` para que un test pueda comprobarlo sin levantar el
 * servidor.
 */
internal fun openApiDocument(): JsonObject = buildJsonObject {
    put("openapi", "3.0.3")
    putJsonObject("info") {
        put("title", API_TITLE)
        put("version", API_VERSION)
        put("description", API_DESCRIPTION)
    }
    putJsonArray("servers") {
        addJsonObject {
            put("url", "/")
            put("description", "Mismo origen que sirve la API.")
        }
    }
    putJsonArray("tags") {
        TAGS.forEach { (name, description) ->
            addJsonObject {
                put("name", name)
                put("description", description)
            }
        }
    }
    putJsonObject("components") {
        putJsonObject("securitySchemes") {
            putJsonObject(BEARER_SCHEME) {
                put("type", "http")
                put("scheme", "bearer")
                put("bearerFormat", "JWT")
                put(
                    "description",
                    "Token de acceso de `POST /v1/auth/login`, en la cabecera " +
                        "`Authorization: Bearer <token>`. Vive 15 minutos (`expiresIn`); " +
                        "se renueva con `POST /v1/auth/refresh`. Sin token, la respuesta es " +
                        "`401 UNAUTHENTICATED` con el contrato de error común.",
                )
            }
            putJsonObject(SERVICE_KEY_SCHEME) {
                put("type", "apiKey")
                put("in", "header")
                put("name", "X-Service-Key")
                put(
                    "description",
                    "Credencial de servicio para integraciones sin persona detrás " +
                        "(la impresora Zebra). Va en su propia cabecera y no en " +
                        "`Authorization` para que la auditoría distinga `SERVICE_PRINTER` de " +
                        "un usuario. Ninguna ruta de esta versión la usa todavía: se declara " +
                        "porque el contrato la reserva para `PATCH /v1/print-jobs/{id}`.",
                )
            }
        }
        putJsonObject("schemas") { schemas() }
    }
    putJsonObject("paths") { documentRoutes() }
    putJsonArray("x-contract-divergences") {
        CONTRACT_DIVERGENCES.forEach { add(it) }
    }
}

private val TAGS: List<Pair<String, String>> = listOf(
    TAG_AUTH to "Inicio y cierre de sesión, renovación del token y contraseña. " +
        "Todo el grupo es público salvo `/me` y `/change-password`.",
    TAG_PRODUCTS to "Catálogo de productos (el maestro). El stock NO se escribe aquí.",
    TAG_LOCATIONS to "Ubicaciones físicas del almacén (estantes y la ubicación de recepción).",
    TAG_WAREHOUSES to "Bodegas. Catálogo cerrado y pequeño: se devuelve entero, sin paginar.",
    TAG_INVENTORY to "Existencias, kardex y las operaciones que mueven inventario.",
    TAG_INVOICES to "Recepción de mercancía: la factura, sus líneas, la captura del conteo " +
        "físico y la aprobación de discrepancias.",
    TAG_IDENTITY to "Administración de personas, roles y el catálogo de permisos.",
    TAG_AUDIT to "Registro de auditoría. Solo lectura: el ledger es append-only.",
    TAG_REPORTS to "Reportes de reconciliación.",
    TAG_HEALTH to "Estado del servicio. Público y sin autenticación.",
)

private const val TAG_AUTH = "Autenticación"
private const val TAG_PRODUCTS = "Catálogo · Productos"
private const val TAG_LOCATIONS = "Catálogo · Ubicaciones"
private const val TAG_WAREHOUSES = "Catálogo · Bodegas"
private const val TAG_INVENTORY = "Inventario"
private const val TAG_INVOICES = "Recepción · Facturas"
private const val TAG_IDENTITY = "Identidad · Administración"
private const val TAG_AUDIT = "Auditoría"
private const val TAG_REPORTS = "Reportes"
private const val TAG_HEALTH = "Salud"

private val API_DESCRIPTION = """
    API del sistema de almacén integral. Todas las rutas cuelgan de `/v1`: el
    legacy no versionaba, así que no se podía cambiar un contrato sin romper los
    clientes ya desplegados.

    ## Errores: el cliente decide con `code`, nunca con el mensaje

    Todas las respuestas de error comparten el mismo cuerpo:

    ```json
    { "code": "POLICY_DENIED", "message": "...", "details": { "campo": "motivo" } }
    ```

    `code` es estable y está en `SCREAMING_SNAKE_CASE`. `message` se traduce y se
    reescribe: compararlo rompe al cliente en silencio. El catálogo de códigos es
    una **lista cerrada** y viaja como `enum` en el esquema `ErrorResponse`; cada
    operación declara además los suyos en la extensión `x-error-codes`. Algunos
    ejemplos de ese catálogo: `INVALID_PAGINATION`, `INVALID_SORT`,
    `INSUFFICIENT_STOCK`, `REASON_REQUIRED`, `SEGREGATION_OF_DUTIES`,
    `DUPLICATE_PRODUCT_CODE`, `LOCATION_HAS_STOCK`.

    Los cinco códigos de autorización se distinguen a propósito, porque la acción
    del usuario es distinta en cada caso: `UNAUTHENTICATED` (iniciar sesión),
    `PERMISSION_DENIED` (pedir el permiso), `SCOPE_INSUFFICIENT` (pedir acceso a
    esa bodega), `POLICY_DENIED` (cambiar cuándo o cómo) y
    `SEGREGATION_OF_DUTIES` (que lo apruebe otra persona).

    ## Paginación

    Toda lista se pagina con `page` (base 0), `size` (50 por defecto, **máximo
    200: un `size` mayor se rechaza con `400 INVALID_PAGINATION`, no se recorta
    en silencio**: recortar haría creer al cliente que tiene todos los datos) y
    `sort` como `campo,direccion` (un campo no admitido: `400 INVALID_SORT`). El
    sobre es siempre `{ items, page, size, totalItems, totalPages }`.

    Dos excepciones deliberadas, porque son catálogos cerrados que la interfaz
    necesita completos: `GET /v1/warehouses` y `GET /v1/permissions`.

    ## Idempotencia

    Las operaciones que mueven inventario aceptan la cabecera `Idempotency-Key`.
    Repetir la misma clave devuelve la misma respuesta y **no repite el efecto**.
    No va en el cuerpo: va en la cabecera.

    ## Cabeceras

    | Cabecera | Para qué |
    |---|---|
    | `Authorization: Bearer <jwt>` | identidad |
    | `Idempotency-Key: <uuid>` | deduplicar reintentos |
    | `X-Request-Id: <uuid>` | correlación con la auditoría |
    | `X-Client-Channel` | `WEB`, `MOBILE`, `MOBILE_OFFLINE` |
    | `X-Client-Timezone` | IANA, p. ej. `America/Mexico_City` |

    ## Fechas y números

    Instantes en ISO-8601 UTC con `Z`; fechas operativas como `AAAA-MM-DD`.

    **Las cantidades viajan como cadena decimal de 3 decimales** (`"100.000"`), no
    como número. `CONTRATO_API.md` §1.7 dice lo contrario y es una de las
    divergencias documentadas abajo: se documenta lo que el servidor devuelve.
""".trimIndent()

/**
 * Divergencias reales entre `CONTRATO_API.md` y la implementación, encontradas
 * leyendo las rutas y los DTO. Se publican en el documento para que un cliente
 * que se genere a partir de aquí no herede la promesa equivocada.
 */
internal val CONTRACT_DIVERGENCES: List<String> = listOf(
    "§9: el contrato promete {checkedAt, productsChecked, divergences, items[{productCode, ledgerNet, projected, difference}]}. La ruta devuelve {checkedAt, cuadra (booleano), divergences (entero), items[{productId, productCode, totalEnBd, totalEsperado, diferencia}]}. No existen productsChecked ni ledgerNet ni projected; sí existe cuadra.",
    "§9: `items` contiene SOLO las divergencias, no todos los productos comprobados. Un `items` vacío con `cuadra: true` es el estado sano.",
    "§1.7 contra las rutas: las cantidades se serializan como CADENA de 3 decimales (CantidadJson -> \"5193.000\", DivergenciaDto.totalEnBd). El contrato dice «número decimal, nunca cadena».",
    "§4: POST /v1/inventory/adjustments documenta {productId, locationId, direction: IN|OUT, idempotencyKey en el cuerpo}. La implementación recibe {productCode, locationCode, direction: INCREASE|DECREASE, reason, origin, quantity, note?, approverId?} y la idempotencia va en la cabecera Idempotency-Key.",
    "§4: un ajuste que queda PENDING_APPROVAL responde 202 Accepted (no 201 como dice el contrato).",
    "§4: POST /v1/inventory/locate documenta {invoiceLineId, toLocationId, quantity, idempotencyKey}; la implementación recibe {productCode, fromLocationCode, toLocationCode, quantity}. No existe invoiceLineId.",
    "§4: POST /v1/inventory/move recibe {productCode, fromLocationCode, toLocationCode, quantity}, no {productId, fromLocationId, toLocationId, quantity}.",
    "§4: GET /v1/inventory/stock filtra por productCode, locationCode y onlyWithStock (por defecto true), no por productId/locationId/warehouseId.",
    "§5: GET /v1/invoices (listado) NO existe: solo hay POST (crear). Tampoco existen POST /v1/invoices/{id}/reject-discrepancy ni /close.",
    "§5: POST /v1/invoices responde 201 con {id, invoiceNumber}, no con la factura completa.",
    "§5: el código de error real cuando nada está pendiente de aprobar es NOT_PENDING_APPROVAL (dominio). El contrato §5 dice NO_PENDING_APPROVAL.",
    "§11: GET /v1/inventory/kardex exige el permiso kardex.view; el contrato le asigna inventory.view.",
    "§11: GET /v1/reports/kardex-vs-projection exige report.view y NO está en la tabla §11.",
    "§11: faltan en la tabla GET /v1/warehouses (location.view), GET /v1/users (user.view), GET /v1/roles (role.manage) y GET /v1/permissions (role.manage).",
    "§1.3-bis: la lista cerrada no incluye códigos que el código SÍ emite: INVALID_FILTER, INVALID_FILTER_RANGE, INVALID_FILTER_DATE, INVALID_LOCATION_CODE, PRODUCT_NOT_FOUND, LOCATION_NOT_FOUND, DUPLICATE_WAREHOUSE_CODE, DUPLICATE_KEY, SAME_PASSWORD, WEAK_PASSWORD, NOT_FOUND, INTERNAL_ERROR.",
    "§4/§5: las rutas de surtido (/v1/orders), traslados (/v1/transfers), etiquetas (/v1/labels, /v1/print-jobs) y los reportes de §9 (inventory, kardex/export, movements-summary) NO están implementados todavía.",
)

// ═════════════════════════════════════════════════════════════════════════════
// ESQUEMAS
// ═════════════════════════════════════════════════════════════════════════════

private fun JsonObjectBuilder.schemas() {
    put("ErrorResponse", errorSchema())
    put("LoginRequest", objectSchema(
        "username" to string("Usuario.", example = "jperez"),
        "password" to string("Contraseña en claro solo en tránsito.", formato = "password"),
        required = listOf("username", "password"),
    ))
    put("RefreshRequest", objectSchema(
        "refreshToken" to string("Token de refresco vigente."),
        required = listOf("refreshToken"),
    ))
    put("ChangePasswordRequest", objectSchema(
        "currentPassword" to string("Contraseña actual.", formato = "password"),
        "newPassword" to string("Contraseña nueva.", formato = "password"),
        required = listOf("currentPassword", "newPassword"),
    ))
    put("SessionUserDto", objectSchema(
        "id" to integer("Identificador del usuario."),
        "username" to string(example = "jperez"),
        "name" to string(example = "Juan Pérez"),
        "roles" to arrayOfString("Claves de rol, p. ej. `JEFE_BODEGA`."),
        "permissions" to arrayOfString("Permisos efectivos, p. ej. `invoice.approve`."),
        "scopes" to objectSchema(
            description = "Alcance por permiso: `NONE` | `OWN` | `AREA` | `ALL`. " +
                "Es lo que permite responder «tiene el permiso, pero no alcanza esta bodega» " +
                "con `SCOPE_INSUFFICIENT` en lugar de un 403 mudo.",
        ),
        "warehouses" to arrayOfInteger("Bodegas a las que alcanza."),
        "mustChangePassword" to boolean("Si es `true`, el cliente debe llevar a cambiar la contraseña."),
        required = listOf("id", "username", "name", "roles", "permissions", "scopes", "warehouses", "mustChangePassword"),
    ))
    put("AuthSessionDto", objectSchema(
        "accessToken" to string("JWT de acceso.", example = "eyJ..."),
        "refreshToken" to string("Token de refresco. ROTA en cada uso: el anterior queda revocado."),
        "expiresIn" to integer("Segundos de vida del token de acceso (900).", formato = "int64", example = "900"),
        "user" to ref("SessionUserDto"),
        required = listOf("accessToken", "refreshToken", "expiresIn", "user"),
    ))
    put("ProductResponse", objectSchema(
        "id" to integer(null),
        "productCode" to string("Alfabeto `0-9A-Z`, longitud 6–14. Inmutable.", example = "ABRAA1B"),
        "name" to string(null, example = "ABRAZADERA DE ARRANQUE 1-5/8\""),
        "description" to string("Nula si no se informó.", nullable = true),
        "unitMeasure" to string("Código de unidad de medida del catálogo.", example = "PZA"),
        "externalId" to integer("Identificador del ERP, si vino de una importación.", nullable = true),
        "active" to boolean("El borrado es LÓGICO: `DELETE` pone esto en `false`."),
        "stock" to quantity("Total derivado: suma de existencias más tránsito no recibido."),
        "stockInTransit" to quantity("Mercancía en camino, devuelta POR SEPARADO para poder decir «100 en existencia, 20 en camino»."),
        required = listOf("id", "productCode", "name", "unitMeasure", "active", "stock"),
    ))
    put("ProductCreateRequest", objectSchema(
        "productCode" to string("Alfabeto `0-9A-Z`, longitud 6–14.", example = "ABRAA1B"),
        "name" to string("Máximo 120 caracteres."),
        "description" to string(nullable = true),
        "unitMeasure" to string("Debe existir en el catálogo de unidades.", example = "PZA"),
        required = listOf("productCode", "name", "unitMeasure"),
    ))
    put("ProductUpdateRequest", objectSchema(
        "productCode" to string("Si viene y es DISTINTO del actual: `409 PRODUCT_CODE_IMMUTABLE`.", nullable = true, example = "ABRAA1B"),
        "name" to string("Obligatorio: un `PUT` reemplaza al recurso."),
        "description" to string("Omitirla o enviarla nula BORRA la descripción: es la única forma de borrarla.", nullable = true),
        "unitMeasure" to string(example = "PZA"),
        required = listOf("name", "unitMeasure"),
    ))
    put("MotivoDeBorradoRequest", objectSchema(
        "reason" to string(
            "Motivo de la desactivación. Obligatorio al borrar un PRODUCTO; opcional al borrar una UBICACIÓN.",
            nullable = true,
        ),
        required = emptyList(),
    ))
    put("ImportReportResponse", objectSchema(
        "dryRun" to boolean("`true` = simulación: no se escribió nada."),
        "totalRows" to integer("Filas de datos del archivo (sin la cabecera)."),
        "accepted" to integer(null),
        "rejected" to integer(null),
        "totalStock" to quantity("Suma del stock del archivo. Un import de catálogo no carga existencias salvo que se pida."),
        "zeroStockCount" to integer("Filas con stock cero."),
        "unitMeasures" to arrayOfString("Unidades encontradas en el archivo."),
        "rejections" to arrayOf(ref("RowRejectionResponse")),
        "persisted" to integer("Filas escritas de verdad (0 con `dryRun`)."),
        "stockPersisted" to boolean("Si además se persistió el stock."),
        required = listOf("dryRun", "totalRows", "accepted", "rejected", "rejections", "persisted"),
    ))
    put("RowRejectionResponse", objectSchema(
        "rowNumber" to integer("Número de fila FÍSICO del archivo (la cabecera es la 1). Es lo que el usuario ve en Excel."),
        "productCode" to string("Cuando el rechazo se puede atribuir a un producto.", nullable = true),
        "reason" to string("Texto para mostrar. El código de error va en el 409/422 de la operación."),
        required = listOf("rowNumber", "reason"),
    ))
    put("LocationResponse", objectSchema(
        "id" to integer(null),
        "warehouseId" to integer(null),
        "warehouseCode" to string(example = "PRINCIPAL"),
        "hall" to string("Pasillo.", example = "04"),
        "code" to string("Estante.", example = "B"),
        "position" to string("Posición.", example = "C"),
        "levels" to integer("Número de niveles; positivo."),
        "kind" to string("`SHELF` o `RECEIVING`. La de recepción es una ubicación REAL con id real.", enumeration = listOf("SHELF", "RECEIVING")),
        "active" to boolean(null),
        "displayName" to string(
            "DERIVADO por el servidor (`hall-code-position`, o `RECEPCION`). " +
                "El cliente NUNCA compone el nombre visible: lo recibe.",
            example = "04-B-C",
        ),
        required = listOf("id", "warehouseId", "warehouseCode", "hall", "code", "position", "levels", "kind", "active", "displayName"),
    ))
    put("LocationCreateRequest", objectSchema(
        "warehouseId" to integer(null),
        "hall" to string(example = "04"),
        "code" to string(example = "B"),
        "position" to string(example = "C"),
        "levels" to integer("Positivo. `422 INVALID_LEVELS` si no lo es.", nullable = true),
        "kind" to string("`SHELF` (por defecto) o `RECEIVING`.", nullable = true, enumeration = listOf("SHELF", "RECEIVING")),
        required = listOf("warehouseId", "hall", "code", "position"),
    ))
    put("LocationUpdateRequest", objectSchema(
        "warehouseId" to integer("Cambiarlo se rechaza: `409 WAREHOUSE_IMMUTABLE`.", nullable = true),
        "hall" to string(nullable = true),
        "code" to string(nullable = true),
        "position" to string(nullable = true),
        "levels" to integer(nullable = true),
        "kind" to string(nullable = true, enumeration = listOf("SHELF", "RECEIVING")),
        "active" to boolean(nullable = true),
    ))
    put("WarehouseResponse", objectSchema(
        "id" to integer(null),
        "code" to string(example = "PRINCIPAL"),
        "name" to string(example = "Bodega principal"),
        "isDefault" to boolean(null),
        "active" to boolean(null),
        required = listOf("id", "code", "name", "isDefault", "active"),
    ))
    put("WarehouseListResponse", objectSchema(
        "items" to arrayOf(ref("WarehouseResponse")),
        required = listOf("items"),
        description = "SIN sobre de paginación, y es deliberado: catálogo cerrado y diminuto que " +
            "la interfaz necesita completo para poblar un desplegable.",
    ))
    put("ExistenciaDto", objectSchema(
        "productId" to integer(null),
        "productCode" to string(example = "ABRAA1B"),
        "productName" to string(null),
        "locationId" to integer(null),
        "locationDisplayName" to string("Derivado por el servidor.", example = "04-B-C"),
        "warehouseId" to integer(null),
        "warehouseCode" to string(example = "PRINCIPAL"),
        "quantity" to quantity("Existencia en esa ubicación."),
        required = listOf("productId", "productCode", "locationId", "locationDisplayName", "quantity"),
    ))
    put("KardexDto", objectSchema(
        "id" to integer(null),
        "movedAt" to string("Instante ISO-8601 UTC.", formato = "date-time"),
        "productCode" to string(null),
        "productName" to string(null),
        "moveType" to string("Tipo de movimiento del dominio, p. ej. `RECEIPT_IN`.", example = "RECEIPT_IN"),
        "units" to quantity("Unidades del movimiento."),
        "quantityAfter" to quantity("Saldo del producto DESPUÉS del movimiento."),
        "fromLocationDisplayName" to string(nullable = true),
        "toLocationDisplayName" to string(nullable = true),
        "container" to string(nullable = true),
        "orderNumber" to string(nullable = true),
        "adjustmentReason" to string(nullable = true),
        "adjustmentOrigin" to string(nullable = true),
        "userId" to integer(nullable = true),
        "userName" to string(nullable = true),
        "note" to string(nullable = true),
        required = listOf("id", "movedAt", "productCode", "moveType", "units", "quantityAfter"),
    ))
    put("AjusteRequest", objectSchema(
        "productCode" to string("Código del producto.", example = "ABRAA1B"),
        "locationCode" to string("Ubicación afectada.", example = "04-B-C"),
        "quantity" to number("Cantidad a mover, con 3 decimales.", example = "5.000"),
        "direction" to string("`INCREASE` suma, `DECREASE` resta. El permiso que se exige depende de esto.", enumeration = listOf("INCREASE", "DECREASE")),
        "reason" to string(
            "Motivo. OBLIGATORIO siempre: sin él, `422 REASON_REQUIRED`.",
            enumeration = listOf(
                "SHORTAGE", "DAMAGE", "THEFT", "SUPPLIER_ERROR", "COUNT_ERROR",
                "RETURN_TO_SUPPLIER", "EXPIRED", "OTHER",
            ),
        ),
        "origin" to string(
            "De dónde nace el ajuste.",
            enumeration = listOf("INVOICE_DISCREPANCY", "FULFILLMENT_SHORTAGE", "MANUAL_COUNT", "DAMAGE_REPORT"),
        ),
        "note" to string(nullable = true),
        "approverId" to integer(
            "Quién aprueba, si el ajuste supera el umbral. Va en el CUERPO y no se " +
                "deduce del token porque tiene que ser OTRA persona: la segregación de " +
                "funciones lo comprueba.",
            nullable = true,
        ),
        required = listOf("productCode", "locationCode", "quantity", "direction", "reason", "origin"),
    ))
    put("TrasladoRequest", objectSchema(
        "productCode" to string(example = "ABRAA1B"),
        "fromLocationCode" to string("Origen.", example = "RECEPCION"),
        "toLocationCode" to string("Destino. Igual al origen: `422 SAME_SOURCE_AND_DESTINATION`.", example = "04-B-C"),
        "quantity" to number(example = "100.000"),
        required = listOf("productCode", "fromLocationCode", "toLocationCode", "quantity"),
    ))
    put("MovimientoResponse", objectSchema(
        "kardexIds" to arrayOfInteger("Identificadores de los movimientos escritos en el kardex."),
        "status" to string("`APPLIED` o `PENDING_APPROVAL`.", enumeration = listOf("APPLIED", "PENDING_APPROVAL")),
        "total" to quantity(
            "Saldo resultante, como CADENA de 3 decimales. En un traslado viaja vacío: " +
                "el total NO cambia, y el mensaje lo dice.",
        ),
        "message" to string("Explicación para mostrar, cuando hay algo que explicar.", nullable = true),
        required = listOf("kardexIds", "status", "total"),
    ))
    put("NuevaFacturaRequest", objectSchema(
        "invoiceNumber" to string("Obligatorio. Una factura sin número no se puede referenciar."),
        "receiptDate" to string("Fecha operativa `AAAA-MM-DD`.", formato = "date"),
        "invoiceType" to string("Por defecto `NATIONAL`.", byDefault = "NATIONAL"),
        "lines" to arrayOf(ref("NuevaLineaRequest"), "Al menos una: `400 FIELD_REQUIRED` si viene vacío."),
        required = listOf("invoiceNumber", "receiptDate"),
    ))
    put("NuevaLineaRequest", objectSchema(
        "productCode" to string(null),
        "container" to string(nullable = true),
        "unitMeasure" to string(null),
        "quantity" to number(null),
        "boxQuantity" to number(null),
        "unitsPerBox" to number(nullable = true),
        required = listOf("productCode", "unitMeasure", "quantity", "boxQuantity"),
    ))
    put("FacturaCreadaResponse", objectSchema(
        "id" to integer(null),
        "invoiceNumber" to string(null),
        required = listOf("id", "invoiceNumber"),
        description = "La creación devuelve la referencia, NO la factura completa: para el detalle " +
            "hay que pedir `GET /v1/invoices/{id}`.",
    ))
    put("CapturarLineaRequest", objectSchema(
        "physicalQuantity" to number("Lo contado físicamente."),
        "physicalBoxQuantity" to number("Cajas contadas, si se cuentan por caja.", nullable = true),
        required = listOf("physicalQuantity"),
        description = "La idempotencia NO va en el cuerpo: va en la cabecera `Idempotency-Key`.",
    ))
    put("AprobarFacturaRequest", objectSchema(
        "adjustmentReason" to string("Obligatorio: `422 REASON_REQUIRED` si falta.", nullable = false),
        "note" to string(nullable = true),
        required = listOf("adjustmentReason"),
    ))
    put("FacturaDetalle", objectSchema(
        "id" to integer(null),
        "invoiceNumber" to string(null),
        "receiptDate" to string(null, formato = "date"),
        "invoiceType" to string(null),
        "status" to string(
            "`PENDING` | `IN_PROGRESS` | `PENDING_TO_LOCATE` | `COMPLETED` | `CANCELLED`.",
            enumeration = listOf("PENDING", "IN_PROGRESS", "PENDING_TO_LOCATE", "COMPLETED", "CANCELLED"),
        ),
        "customerCode" to string(nullable = true),
        "concept" to string(nullable = true),
        "totalLines" to integer(null),
        "pendingApprovalLines" to integer(null),
        "lines" to arrayOf(ref("LineaDto")),
        required = listOf("id", "invoiceNumber", "status", "lines"),
    ))
    put("LineaDto", objectSchema(
        "id" to integer(null),
        "productId" to integer(null),
        "productCode" to string(null),
        "productName" to string(null),
        "container" to string(nullable = true),
        "quantity" to quantity("Cantidad según el documento."),
        "boxQuantity" to quantity(null),
        "unitsPerBox" to quantity(nullable = true),
        "physicalQuantity" to quantity("Lo contado. Nulo mientras no se capture.", nullable = true),
        "physicalBoxQuantity" to quantity(nullable = true),
        "locatedQuantity" to quantity(null),
        "difference" to quantity(
            "`physicalQuantity - quantity`, calculada por la BASE con una columna generada: " +
                "es imposible que se desincronice.",
            nullable = true,
        ),
        "status" to string(
            "Unión propia de la línea de factura.",
            enumeration = listOf("PENDING", "CAPTURED", "DISCREPANCY", "PENDING_APPROVAL", "APPROVED", "ASSIGNED", "LOCATED", "CANCELLED"),
        ),
        "capturedBy" to integer(nullable = true),
        "capturedByName" to string(nullable = true),
        "capturedAt" to string(nullable = true, formato = "date-time"),
        "approvedBy" to integer(nullable = true),
        "approvedByName" to string(nullable = true),
        "approvedAt" to string(nullable = true, formato = "date-time"),
        "adjustmentReason" to string(nullable = true),
        "locationDisplayName" to string(nullable = true),
        required = listOf("id", "productId", "productCode", "quantity", "locatedQuantity", "status"),
    ))
    put("PendienteDto", objectSchema(
        "invoiceId" to integer(null),
        "invoiceNumber" to string(null),
        "receiptDate" to string(null, formato = "date"),
        "status" to string(null),
        "pendingLines" to integer("Líneas en discrepancia sin aprobar."),
        "totalDifference" to quantity("Suma de las diferencias absolutas pendientes."),
        required = listOf("invoiceId", "invoiceNumber", "receiptDate", "status", "pendingLines", "totalDifference"),
    ))
    put("PendientesResponse", objectSchema(
        "items" to arrayOf(ref("PendienteDto")),
        required = listOf("items"),
        description = "SIN sobre de paginación en la implementación actual.",
    ))
    put("UsuarioAdmin", objectSchema(
        "id" to integer(null),
        "username" to string(null),
        "name" to string(null),
        "roles" to arrayOfString(null),
        "active" to boolean(null),
        "lastLoginAt" to string("Nulo si nunca entró.", nullable = true, formato = "date-time"),
        required = listOf("id", "username", "name", "roles", "active"),
    ))
    put("RolAdmin", objectSchema(
        "id" to integer(null),
        "key" to string("Clave del rol, p. ej. `JEFE_BODEGA`.", example = "JEFE_BODEGA"),
        "name" to string(null),
        "description" to string(nullable = true),
        "isSystem" to boolean("Los roles de sistema no se borran."),
        "active" to boolean(null),
        "sortOrder" to integer(null),
        "permissionCount" to integer(null),
        "userCount" to integer(null),
        required = listOf("id", "key", "name", "isSystem", "active", "permissionCount", "userCount"),
    ))
    put("PermisoAdmin", objectSchema(
        "key" to string("Clave `recurso.accion`.", example = "invoice.approve"),
        "module" to string(null),
        "resource" to string(null),
        "action" to string(null),
        "name" to string(null),
        "description" to string(nullable = true),
        "scopes" to arrayOfString("Alcances admitidos."),
        "sensitive" to boolean(null),
        "active" to boolean(null),
        "sortOrder" to integer(null),
        required = listOf("key", "module", "resource", "action", "name"),
    ))
    put("ModuloDePermisos", objectSchema(
        "module" to string(null),
        "moduleName" to string(null),
        "permissions" to arrayOf(ref("PermisoAdmin")),
        required = listOf("module", "moduleName", "permissions"),
    ))
    put("CatalogoDePermisosResponse", objectSchema(
        "totalItems" to integer("Total de permisos del catálogo."),
        "modules" to arrayOf(ref("ModuloDePermisos"), "Agrupados por módulo, para la pantalla de roles."),
        "items" to arrayOf(ref("PermisoAdmin"), "La MISMA lista en plano: un selector no tiene que recorrer los módulos."),
        required = listOf("totalItems", "modules", "items"),
    ))
    put("AuditEntryDto", objectSchema(
        "id" to integer(null),
        "sequenceNo" to integer("Orden total del ledger entre particiones."),
        "occurredAt" to string("Instante ISO-8601 UTC.", formato = "date-time"),
        "businessDate" to string("Fecha de negocio: un cierre capturado de madrugada pertenece al día anterior.", formato = "date"),
        "userId" to integer(nullable = true),
        "username" to string(nullable = true),
        "userName" to string(nullable = true),
        "roles" to arrayOfString("Instantánea del rol en el momento del hecho."),
        "action" to string(null),
        "actionLevel" to string(null),
        "entity" to string(null),
        "entityId" to string(nullable = true),
        "entityLabel" to string(nullable = true),
        "changedFields" to arrayOfString(null),
        "channel" to string("`WEB`, `MOBILE`, `MOBILE_OFFLINE` o `SERVICE_PRINTER`.", nullable = true),
        "device" to string(nullable = true),
        "ip" to string(nullable = true),
        "userAgent" to string(nullable = true),
        "permission" to string(nullable = true),
        "decision" to string(nullable = true),
        "result" to string(null),
        "errorCode" to string(nullable = true),
        "errorMessage" to string(nullable = true),
        "reason" to string(nullable = true),
        "correlationId" to string(nullable = true),
        required = listOf("id", "sequenceNo", "occurredAt", "businessDate", "action", "entity", "result"),
    ))
    put("DivergenciaDto", objectSchema(
        "productId" to integer(null),
        "productCode" to string(null),
        "totalEnBd" to quantity("Lo que dice la proyección de existencias."),
        "totalEsperado" to quantity("Lo que dice el ledger, que es la fuente de verdad."),
        "diferencia" to quantity("`totalEnBd - totalEsperado`."),
        required = listOf("productId", "productCode", "totalEnBd", "totalEsperado", "diferencia"),
    ))
    put("ReporteReconciliacion", objectSchema(
        "checkedAt" to string("Instante en que se comprobó.", formato = "date-time"),
        "cuadra" to boolean("`true` cuando NO hay divergencias. Booleano explícito para no obligar a contar el arreglo."),
        "divergences" to integer("Número de productos descuadrados."),
        "items" to arrayOf(ref("DivergenciaDto"), "SOLO los descuadres, no todos los productos comprobados."),
        required = listOf("checkedAt", "cuadra", "divergences", "items"),
    ))
    put("HealthResponse", objectSchema(
        "status" to string("`UP` o `DOWN`. Con `DOWN` la respuesta es `503`.", enumeration = listOf("UP", "DOWN")),
        "schemaVersion" to string(
            "Versión del esquema aplicada. Es diagnóstico: si no se puede leer, el estado " +
                "sigue siendo `UP` y el motivo viaja en `database.error`.",
            nullable = true,
        ),
        "database" to ref("ComponentHealth"),
        "version" to string("Versión desplegada (`STOCK_VERSION`, o `dev`).", example = "1.0.0"),
        required = listOf("status", "database", "version"),
    ))
    put("ComponentHealth", objectSchema(
        "status" to string("`UP` o `DOWN`.", enumeration = listOf("UP", "DOWN")),
        "latencyMs" to integer("Latencia de la comprobación, en milisegundos."),
        "error" to string("Motivo, cuando hay algo que decir.", nullable = true),
        required = listOf("status", "latencyMs"),
    ))
}

private fun errorSchema(): JsonObject = objectSchema(
    "code" to string(
        "Código ESTABLE del catálogo cerrado. El cliente decide con esto, nunca con `message`.",
        enumeration = ERROR_CODES_CATALOG,
    ),
    "message" to string("Texto para mostrar. Se traduce y se reescribe: no comparar."),
    "details" to objectSchema(
        description = "Detalles por campo o por contexto (`available`, `until`, `stock`, " +
            "`pendingLines`, `allowed`…). Su forma depende del código.",
    ),
    required = listOf("code", "message"),
    description = "Cuerpo de error común a TODOS los endpoints (CONTRATO_API.md §1.3). " +
        "Los códigos de autorización se distinguen para que el usuario sepa si debe iniciar " +
        "sesión, pedir un permiso, pedir acceso a una bodega, cambiar cómo lo hace o llamar " +
        "a otra persona.",
)

/**
 * Catálogo de códigos de error.
 *
 * Los primeros son la lista cerrada de `CONTRATO_API.md` §1.3-bis (más los de
 * autenticación de §2 y los de inventario/recepción de §4 y §5). Los últimos,
 * marcados aparte, son códigos que **el código emite hoy y el contrato no
 * lista**: se incluyen aquí porque el cliente tiene que poder contemplarlos, y
 * se reportan como divergencia en lugar de esconderlos.
 */
internal val ERROR_CODES_CATALOG: List<String> = listOf(
    // Autorización (§1.3)
    "UNAUTHENTICATED", "PERMISSION_DENIED", "SCOPE_INSUFFICIENT", "POLICY_DENIED", "SEGREGATION_OF_DUTIES",
    // Petición (§1.4)
    "INVALID_PAGINATION", "INVALID_SORT", "INVALID_ID", "INVALID_PARAMETER",
    "FILE_REQUIRED", "FILE_TOO_LARGE", "EMPTY_FILE", "IMPORT_FILE_FORMAT", "INVALID_DRY_RUN",
    // Validación de campo (§422)
    "FIELD_REQUIRED", "FIELD_TOO_LONG", "INVALID_PRODUCT_CODE", "UNKNOWN_UNIT_MEASURE",
    "INVALID_LEVELS", "INVALID_LOCATION_KIND", "INVALID_LOCATION_PART", "UNKNOWN_WAREHOUSE",
    "IMPORT_INVALID_EXTERNAL_ID",
    // Conflicto con el estado (§409)
    "DUPLICATE_PRODUCT_CODE", "DUPLICATE_EXTERNAL_ID", "DUPLICATE_LOCATION",
    "PRODUCT_CODE_IMMUTABLE", "WAREHOUSE_IMMUTABLE", "PRODUCT_ALREADY_INACTIVE",
    "LOCATION_ALREADY_INACTIVE", "RECEIVING_LOCATION_PROTECTED", "LOCATION_HAS_STOCK",
    "PRODUCT_HAS_STOCK", "IMPORT_HAS_REJECTIONS",
    // Autenticación (§2)
    "INVALID_CREDENTIALS", "ACCOUNT_DISABLED", "ACCOUNT_LOCKED", "RATE_LIMITED", "TOKEN_REUSED",
    "SAME_PASSWORD", "WEAK_PASSWORD",
    // Inventario y recepción (§4, §5)
    "REASON_REQUIRED", "INSUFFICIENT_STOCK", "SAME_SOURCE_AND_DESTINATION",
    "DISCREPANCY_NOT_APPROVED", "NOT_PENDING_APPROVAL", "HAS_PENDING_LINES",
    "INVALID_QUANTITY", "INCONSISTENT_BOX_COUNT", "BOXES_DO_NOT_DIVIDE_EVENLY",
    "EXCEEDS_LINE_QUANTITY", "INVALID_TRANSITION", "INVOICE_CLOSED",
    "CANNOT_DELETE_WITH_INVENTORY_EFFECT", "ACCESS_DENIED",
    // Emitidos por el código y AUSENTES en §1.3-bis (divergencia reportada)
    "INVALID_FILTER", "INVALID_FILTER_RANGE", "INVALID_FILTER_DATE", "INVALID_LOCATION_CODE",
    "PRODUCT_NOT_FOUND", "LOCATION_NOT_FOUND", "DUPLICATE_WAREHOUSE_CODE", "DUPLICATE_KEY",
    "NOT_FOUND", "INTERNAL_ERROR",
)

// ═════════════════════════════════════════════════════════════════════════════
// UTILIDADES DE ESQUEMA
// ═════════════════════════════════════════════════════════════════════════════

private fun ref(name: String): JsonObject = buildJsonObject {
    put("\$ref", "#/components/schemas/$name")
}

private fun schema(
    type: String?,
    description: String? = null,
    formato: String? = null,
    example: String? = null,
    enumeration: List<String> = emptyList(),
    nullable: Boolean = false,
    byDefault: JsonElement? = null,
): JsonObject = buildJsonObject {
    type?.let { put("type", it) }
    formato?.let { put("format", it) }
    description?.let { put("description", it) }
    if (enumeration.isNotEmpty()) putJsonArray("enum") { enumeration.forEach { add(it) } }
    example?.let { put("example", it) }
    byDefault?.let { put("default", it) }
    if (nullable) put("nullable", true)
}

private fun string(
    description: String? = null,
    formato: String? = null,
    example: String? = null,
    enumeration: List<String> = emptyList(),
    nullable: Boolean = false,
    byDefault: String? = null,
): JsonObject = schema(
    type = "string",
    description = description,
    formato = formato,
    example = example,
    enumeration = enumeration,
    nullable = nullable,
    byDefault = byDefault?.let { kotlinx.serialization.json.JsonPrimitive(it) },
)

private fun integer(
    description: String? = null,
    formato: String = "int64",
    example: String? = null,
    nullable: Boolean = false,
    byDefault: Int? = null,
): JsonObject = schema(
    type = "integer",
    description = description,
    formato = formato,
    example = example,
    nullable = nullable,
    byDefault = byDefault?.let { kotlinx.serialization.json.JsonPrimitive(it) },
)

private fun number(
    description: String? = null,
    example: String? = null,
    nullable: Boolean = false,
): JsonObject = schema(
    type = "number",
    description = description,
    formato = "double",
    example = example,
    nullable = nullable,
)

/**
 * Cantidad monetaria/de existencias.
 *
 * **`type: string` y no `number`**, porque es lo que el servidor devuelve de
 * verdad: `CantidadJson` se serializa como texto de 3 decimales. Documentarlo
 * como número haría que un cliente generado desde aquí fallara al parsear.
 */
private fun quantity(description: String? = null, nullable: Boolean = false): JsonObject = schema(
    type = "string",
    description = listOfNotNull(
        "Cantidad decimal con 3 decimales, serializada como CADENA.",
        description,
    ).joinToString(" "),
    example = "100.000",
    nullable = nullable,
)

private fun boolean(description: String? = null, nullable: Boolean = false): JsonObject =
    schema(type = "boolean", description = description, nullable = nullable)

private fun arrayOf(elemento: JsonObject, description: String? = null): JsonObject = buildJsonObject {
    put("type", "array")
    description?.let { put("description", it) }
    put("items", elemento)
}

private fun arrayOf(elemento: String, description: String? = null): JsonObject =
    arrayOf(ref(elemento), description)

private fun arrayOfString(description: String? = null): JsonObject =
    arrayOf(string(), description)

private fun arrayOfInteger(description: String? = null): JsonObject =
    arrayOf(integer(), description)

private fun objectSchema(
    vararg properties: Pair<String, JsonObject>,
    required: List<String> = emptyList(),
    description: String? = null,
    nullable: Boolean = false,
): JsonObject = buildJsonObject {
    put("type", "object")
    description?.let { put("description", it) }
    if (nullable) put("nullable", true)
    if (properties.isNotEmpty()) {
        putJsonObject("properties") {
            properties.forEach { (name, schema) -> put(name, schema) }
        }
    }
    if (required.isNotEmpty()) {
        putJsonArray("required") { required.forEach { add(it) } }
    }
}

private fun pageEnvelope(itemRef: String): JsonObject = objectSchema(
    "items" to arrayOf(ref(itemRef)),
    "page" to integer("Página solicitada (base 0)."),
    "size" to integer("Tamaño de página."),
    "totalItems" to integer("Total de filas que cumplen el filtro, NO el tamaño de `items`."),
    "totalPages" to integer("Número de páginas. Con cero resultados son cero páginas, no una."),
    required = listOf("items", "page", "size", "totalItems", "totalPages"),
)

// ═════════════════════════════════════════════════════════════════════════════
// PARÁMETROS
// ═════════════════════════════════════════════════════════════════════════════

private fun route(name: String, schema: JsonObject, description: String? = null): JsonObject =
    buildJsonObject {
        put("name", name)
        put("in", "path")
        put("required", true)
        description?.let { put("description", it) }
        put("schema", schema)
    }

private fun query(
    name: String,
    schema: JsonObject,
    description: String? = null,
    required: Boolean = false,
): JsonObject = buildJsonObject {
    put("name", name)
    put("in", "query")
    put("required", required)
    description?.let { put("description", it) }
    put("schema", schema)
}

private fun header(name: String, description: String, schema: JsonObject = string()): JsonObject =
    buildJsonObject {
        put("name", name)
        put("in", "header")
        put("required", false)
        put("description", description)
        put("schema", schema)
    }

/**
 * Paginación de `CONTRATO_API.md` §1.4, generada desde los campos ordenables
 * REALES de cada repositorio.
 */
private fun pagination(fieldsSortable: List<String>, defaultOrder: String): List<JsonObject> = listOf(
    query("page", integer("Base 0.", byDefault = 0), "Página solicitada, empezando en 0."),
    query(
        "size",
        integer("Por defecto 50, máximo 200.", byDefault = 50),
        "Tamaño de página. **Un `size` mayor que 200 se RECHAZA con `400 INVALID_PAGINATION`, " +
            "no se recorta**: recortar haría creer al cliente que tiene todos los datos.",
    ),
    query(
        "sort",
        string("`campo,direccion`.", enumeration = fieldsSortable.flatMap { listOf("$it,asc", "$it,desc") }),
        "Campo y dirección. Admitidos en este endpoint: ${fieldsSortable.joinToString(", ")}. " +
            "Por defecto `$defaultOrder`. Un campo no admitido: `400 INVALID_SORT`.",
    ),
)

private fun search(): JsonObject = query("q", string(), "Búsqueda libre.")

private fun activeFilter(): JsonObject =
    query("active", boolean("Filtra por activo/inactivo. Un valor no booleano: `400 INVALID_FILTER`."))

private val clientHeaders: List<JsonObject> = listOf(
    header("X-Request-Id", "UUID de correlación. Se propaga a la auditoría."),
    header("X-Client-Channel", "`WEB`, `MOBILE` o `MOBILE_OFFLINE`. La auditoría registra el canal, y `MOBILE_OFFLINE` dispara el camino de sincronización diferida.", string("WEB", enumeration = listOf("WEB", "MOBILE", "MOBILE_OFFLINE"))),
    header("X-Client-Timezone", "Zona IANA, p. ej. `America/Mexico_City`.", string(example = "America/Mexico_City")),
)

private val idempotencyHeader: JsonObject = header(
    "Idempotency-Key",
    "UUID de la operación. Repetir la misma clave devuelve la MISMA respuesta y no repite el " +
        "efecto. Es lo que hace seguro el reintento del móvil sin señal. Un valor que no es UUID " +
        "se rechaza con 400, no con 500.",
    string(formato = "uuid"),
)

// ═════════════════════════════════════════════════════════════════════════════
// OPERACIONES
// ═════════════════════════════════════════════════════════════════════════════

private enum class Security { PUBLICA, BEARER, SERVICE_KEY }

private class Response(
    val code: String,
    val description: String,
    val schema: JsonObject? = null,
)

private class Operation(
    val method: String,
    val route: String,
    val label: String,
    val resumen: String,
    val description: String = "",
    val security: Security = Security.BEARER,
    val permission: String? = null,
    val parameters: List<JsonObject> = emptyList(),
    val body: JsonObject? = null,
    val contentType: String = "application/json",
    val responses: List<Response> = listOf(Response("200", "Operación correcta.")),
    val errorCodes: List<String> = emptyList(),
)

private fun JsonObjectBuilder.documentRoutes() {
    ENDPOINTS.groupBy { it.route }.forEach { (route, operations) ->
        putJsonObject(route) {
            operations.forEach { operation ->
                putJsonObject(operation.method.lowercase()) { operationJson(operation) }
            }
        }
    }
}

private fun JsonObjectBuilder.operationJson(operation: Operation) {
    putJsonArray("tags") { add(operation.label) }
    put("summary", operation.resumen)
    if (operation.description.isNotEmpty()) put("description", operation.description)
    put("operationId", operationId(operation))

    if (operation.parameters.isNotEmpty()) {
        putJsonArray("parameters") { operation.parameters.forEach { add(it) } }
    }

    operation.body?.let { schema ->
        putJsonObject("requestBody") {
            put("required", true)
            putJsonObject("content") {
                putJsonObject(operation.contentType) { put("schema", schema) }
            }
        }
    }

    putJsonObject("responses") { respuestasJson(operation) }

    if (operation.security != Security.PUBLICA) {
        putJsonArray("security") {
            addJsonObject {
                putJsonArray(
                    if (operation.security == Security.BEARER) BEARER_SCHEME else SERVICE_KEY_SCHEME,
                ) { }
            }
        }
    }

    operation.permission?.let { put("x-required-permission", it) }
    if (operation.errorCodes.isNotEmpty()) {
        putJsonArray("x-error-codes") { operation.errorCodes.forEach { add(it) } }
    }
}

private fun JsonObjectBuilder.respuestasJson(operation: Operation) {
    val declared = mutableSetOf<String>()

    operation.responses.forEach { response ->
        declared += response.code
        putJsonObject(response.code) {
            put("description", response.description)
            response.schema?.let { schema ->
                putJsonObject("content") {
                    putJsonObject("application/json") { put("schema", schema) }
                }
            }
        }
    }

    if (operation.security != Security.PUBLICA && "401" !in declared) {
        errorResponse(
            "401",
            "Sin identidad válida: falta el token o venció.",
            "UNAUTHENTICATED",
        )
    }
    if (operation.security != Security.PUBLICA && "403" !in declared) {
        errorResponse(
            "403",
            "Identidad sin autorización. El `code` dice QUÉ hacer: pedir el permiso " +
                "(`PERMISSION_DENIED`), pedir acceso a esa bodega (`SCOPE_INSUFFICIENT`), " +
                "cambiar cuándo o cómo (`POLICY_DENIED`) o que lo apruebe otra persona " +
                "(`SEGREGATION_OF_DUTIES`).",
            null,
        )
    }
    if ("500" !in declared) {
        errorResponse(
            "500",
            "Error interno. El incidente queda registrado; el detalle NO se filtra al cliente.",
            "INTERNAL_ERROR",
        )
    }
}

private fun JsonObjectBuilder.errorResponse(
    code: String,
    description: String,
    errorCode: String?,
) {
    putJsonObject(code) {
        put("description", description)
        putJsonObject("content") {
            putJsonObject("application/json") { put("schema", ref("ErrorResponse")) }
        }
        errorCode?.let { put("x-error-code", it) }
    }
}

private fun operationId(operation: Operation): String {
    val clean = operation.route
        .removePrefix("/")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
    return "${operation.method.lowercase()}_$clean"
}

// ═════════════════════════════════════════════════════════════════════════════
// EL CATÁLOGO DE ENDPOINTS
//
// Un renglón por operación real de `backend/src/main/kotlin/.../*Routes.kt`.
// Lo que NO existe no se documenta: ver DIVERGENCIAS_CONTRATO.
// ═════════════════════════════════════════════════════════════════════════════

private val ENDPOINTS: List<Operation> = listOf(

    // ── Autenticación ───────────────────────────────────────────────────────
    Operation(
        method = "POST",
        route = "/v1/auth/login",
        label = TAG_AUTH,
        resumen = "Iniciar sesión",
        description = "Devuelve el token de acceso, el de refresco y el usuario con sus permisos " +
            "efectivos y alcances. `expiresIn` va en SEGUNDOS, que es lo que esperan las " +
            "bibliotecas de cliente.\n\n`INVALID_CREDENTIALS` es el MISMO código para usuario " +
            "inexistente y contraseña incorrecta: distinguirlos permitiría enumerar usuarios.",
        security = Security.PUBLICA,
        body = ref("LoginRequest"),
        responses = listOf(
            Response("200", "Sesión iniciada.", ref("AuthSessionDto")),
        ),
        errorCodes = listOf("FIELD_REQUIRED", "INVALID_CREDENTIALS", "ACCOUNT_DISABLED", "ACCOUNT_LOCKED", "RATE_LIMITED"),
    ),
    Operation(
        method = "POST",
        route = "/v1/auth/refresh",
        label = TAG_AUTH,
        resumen = "Renovar la sesión (con rotación)",
        description = "Devuelve tokens NUEVOS. El refresco ROTA: el presentado queda revocado. " +
            "Si se detecta reuso, se revoca TODA la familia y la respuesta es " +
            "`401 TOKEN_REUSED`, un código distinto de `INVALID_CREDENTIALS` porque la interfaz " +
            "debe decir otra cosa: «tu sesión se cerró por seguridad».",
        security = Security.PUBLICA,
        body = ref("RefreshRequest"),
        responses = listOf(
            Response("200", "Tokens nuevos.", ref("AuthSessionDto")),
            Response(
                "401",
                "Token de refresco inválido, vencido o REUTILIZADO (se revocó la familia).",
                ref("ErrorResponse"),
            ),
        ),
        errorCodes = listOf("TOKEN_REUSED", "UNAUTHENTICATED"),
    ),
    Operation(
        method = "POST",
        route = "/v1/auth/logout",
        label = TAG_AUTH,
        resumen = "Cerrar sesión",
        description = "Revoca el token de refresco presentado y su familia. **Es idempotente y no " +
            "exige token válido**: si el cliente quiere cerrar sesión hay que dejarlo; responder " +
            "401 dejaría la sesión viva justo cuando el usuario pide lo contrario. El cuerpo es " +
            "opcional por eso mismo.",
        security = Security.PUBLICA,
        body = ref("RefreshRequest"),
        responses = listOf(Response("204", "Sesión revocada. Sin cuerpo.")),
    ),
    Operation(
        method = "GET",
        route = "/v1/auth/me",
        label = TAG_AUTH,
        resumen = "El usuario de la sesión actual",
        description = "Es lo que la web usa al recargar para reconstruir permisos y alcances sin " +
            "volver a autenticar.",
        responses = listOf(Response("200", "Usuario de la sesión.", ref("SessionUserDto"))),
        errorCodes = listOf("UNAUTHENTICATED"),
    ),
    Operation(
        method = "POST",
        route = "/v1/auth/change-password",
        label = TAG_AUTH,
        resumen = "Cambiar la contraseña",
        description = "**Invalida TODAS las sesiones, incluida la actual**: el cliente debe volver " +
            "a entrar. `SAME_PASSWORD` rechaza repetir la contraseña actual y `WEAK_PASSWORD` " +
            "rechaza una que no cumple la política.",
        body = ref("ChangePasswordRequest"),
        responses = listOf(Response("204", "Contraseña cambiada y sesiones revocadas. Sin cuerpo.")),
        errorCodes = listOf("UNAUTHENTICATED", "FIELD_REQUIRED", "SAME_PASSWORD", "WEAK_PASSWORD"),
    ),

    // ── Productos ───────────────────────────────────────────────────────────
    Operation(
        method = "GET",
        route = "/v1/products",
        label = TAG_PRODUCTS,
        resumen = "Listar productos",
        description = "`stock` es el total derivado: existencias más tránsito con `received_at IS " +
            "NULL`. La condición no es cosmética: un traslado ya recibido tiene sus unidades en " +
            "las existencias Y su fila de tránsito como histórico, así que sumarlas contaría la " +
            "misma mercancía dos veces. El tránsito se devuelve aparte para poder mostrar " +
            "«100 en existencia, 20 en camino».",
        permission = "product.view",
        parameters = pagination(listOf("productCode", "name", "createdAt"), "productCode") +
            listOf(search(), activeFilter()),
        responses = listOf(Response("200", "Listado paginado.", pageEnvelope("ProductResponse"))),
        errorCodes = listOf("UNAUTHENTICATED", "PERMISSION_DENIED", "SCOPE_INSUFFICIENT", "INVALID_PAGINATION", "INVALID_SORT", "INVALID_FILTER"),
    ),
    Operation(
        method = "POST",
        route = "/v1/products",
        label = TAG_PRODUCTS,
        resumen = "Crear producto",
        permission = "product.create",
        parameters = clientHeaders,
        body = ref("ProductCreateRequest"),
        responses = listOf(
            Response("201", "Producto creado.", ref("ProductResponse")),
            Response("409", "Ya existe ese `productCode`.", ref("ErrorResponse")),
            Response("422", "El cuerpo viola una regla de validación; `details` trae el campo.", ref("ErrorResponse")),
        ),
        errorCodes = listOf(
            "FIELD_REQUIRED", "FIELD_TOO_LONG", "INVALID_PRODUCT_CODE", "UNKNOWN_UNIT_MEASURE",
            "DUPLICATE_PRODUCT_CODE", "DUPLICATE_EXTERNAL_ID",
        ),
    ),
    Operation(
        method = "POST",
        route = "/v1/products/import",
        label = TAG_PRODUCTS,
        resumen = "Importar catálogo desde CSV o XLSX",
        description = "`multipart/form-data` con `file` y `dryRun`.\n\n**Siempre `200`, nunca `201`, " +
            "incluso con `dryRun=false`**: no es la creación de un recurso, es una operación sobre " +
            "una colección que produce un informe, y no hay un recurso único al que apuntar con " +
            "`Location`.\n\nCon `dryRun=true` el informe es el mismo pero **no se escribe nada**. " +
            "Si el import real encuentra filas inválidas, **no escribe ninguna** y responde " +
            "`409 IMPORT_HAS_REJECTIONS`. `rejections[].rowNumber` es obligatorio: sin el número " +
            "de fila física el usuario no puede corregir el archivo.",
        permission = "product.import",
        parameters = clientHeaders,
        body = objectSchema(
            "file" to string("CSV canónico o XLSX.", formato = "binary"),
            "dryRun" to string("`true` simula, `false` escribe.", enumeration = listOf("true", "false")),
            required = listOf("file"),
            description = "Cuerpo `multipart/form-data`.",
        ),
        contentType = "multipart/form-data",
        responses = listOf(
            Response("200", "Informe del import.", ref("ImportReportResponse")),
            Response("409", "El import real encontró filas inválidas y no escribió ninguna.", ref("ErrorResponse")),
        ),
        errorCodes = listOf(
            "FILE_REQUIRED", "FILE_TOO_LARGE", "EMPTY_FILE", "IMPORT_FILE_FORMAT", "INVALID_DRY_RUN",
            "INVALID_PRODUCT_CODE", "UNKNOWN_UNIT_MEASURE", "IMPORT_INVALID_EXTERNAL_ID",
            "DUPLICATE_PRODUCT_CODE", "DUPLICATE_EXTERNAL_ID", "IMPORT_HAS_REJECTIONS",
        ),
    ),
    Operation(
        method = "GET",
        route = "/v1/products/{id}",
        label = TAG_PRODUCTS,
        resumen = "Obtener un producto",
        permission = "product.view",
        parameters = listOf(
            route("id", integer("Identificador numérico."), "Un id no numérico: `400 INVALID_ID`."),
        ),
        responses = listOf(
            Response("200", "Producto.", ref("ProductResponse")),
            Response("404", "No existe un producto con ese id.", ref("ErrorResponse")),
        ),
        errorCodes = listOf("INVALID_ID", "PRODUCT_NOT_FOUND"),
    ),
    Operation(
        method = "PUT",
        route = "/v1/products/{id}",
        label = TAG_PRODUCTS,
        resumen = "Actualizar un producto",
        description = "Es un `PUT`: el cuerpo REEMPLAZA al recurso, así que omitir `description` la " +
            "borra. Es la única forma de borrarla. Cambiar `productCode` se rechaza con " +
            "`409 PRODUCT_CODE_IMMUTABLE`: está impreso en etiquetas y estantes.",
        permission = "product.edit",
        parameters = listOf(route("id", integer())) + clientHeaders,
        body = ref("ProductUpdateRequest"),
        responses = listOf(
            Response("200", "Producto actualizado.", ref("ProductResponse")),
            Response("404", "No existe.", ref("ErrorResponse")),
            Response("409", "El código de producto es inmutable.", ref("ErrorResponse")),
            Response("422", "Validación.", ref("ErrorResponse")),
        ),
        errorCodes = listOf(
            "INVALID_ID", "PRODUCT_NOT_FOUND", "FIELD_REQUIRED", "FIELD_TOO_LONG",
            "INVALID_PRODUCT_CODE", "UNKNOWN_UNIT_MEASURE", "PRODUCT_CODE_IMMUTABLE",
        ),
    ),
    Operation(
        method = "DELETE",
        route = "/v1/products/{id}",
        label = TAG_PRODUCTS,
        resumen = "Desactivar un producto (borrado LÓGICO)",
        description = "No borra: pone `active = false`, y exige `reason` en el cuerpo. Un producto " +
            "con existencias no se desactiva: `409 PRODUCT_HAS_STOCK` con `details.stock`.",
        permission = "product.delete",
        parameters = listOf(route("id", integer())) + clientHeaders,
        body = ref("MotivoDeBorradoRequest"),
        responses = listOf(
            Response("204", "Producto desactivado. Sin cuerpo."),
            Response("409", "Tiene existencias, o ya estaba desactivado.", ref("ErrorResponse")),
            Response("422", "Falta el motivo.", ref("ErrorResponse")),
        ),
        errorCodes = listOf("INVALID_ID", "PRODUCT_NOT_FOUND", "FIELD_REQUIRED", "PRODUCT_HAS_STOCK", "PRODUCT_ALREADY_INACTIVE"),
    ),

    // ── Ubicaciones ─────────────────────────────────────────────────────────
    Operation(
        method = "GET",
        route = "/v1/locations",
        label = TAG_LOCATIONS,
        resumen = "Listar ubicaciones",
        permission = "location.view",
        parameters = pagination(listOf("hall", "code", "position", "createdAt"), "hall") + listOf(
            search(),
            query("warehouseId", integer(), "Filtra por bodega."),
            query("kind", string(enumeration = listOf("SHELF", "RECEIVING")), "Filtra por tipo de ubicación."),
            activeFilter(),
        ),
        responses = listOf(Response("200", "Listado paginado.", pageEnvelope("LocationResponse"))),
        errorCodes = listOf("INVALID_PAGINATION", "INVALID_SORT", "INVALID_FILTER", "INVALID_PARAMETER"),
    ),
    Operation(
        method = "POST",
        route = "/v1/locations",
        label = TAG_LOCATIONS,
        resumen = "Crear ubicación",
        description = "`displayName` lo compone el SERVIDOR (`hall-code-position`; `RECEPCION` para " +
            "la de recepción). El cliente nunca compone el nombre visible: lo recibe.",
        permission = "location.create",
        parameters = clientHeaders,
        body = ref("LocationCreateRequest"),
        responses = listOf(
            Response("201", "Ubicación creada.", ref("LocationResponse")),
            Response("409", "Ya existe esa ubicación en la bodega.", ref("ErrorResponse")),
            Response("422", "Validación de formato o de bodega.", ref("ErrorResponse")),
        ),
        errorCodes = listOf(
            "INVALID_LEVELS", "INVALID_LOCATION_KIND", "INVALID_LOCATION_PART",
            "UNKNOWN_WAREHOUSE", "DUPLICATE_LOCATION",
        ),
    ),
    Operation(
        method = "GET",
        route = "/v1/locations/{id}",
        label = TAG_LOCATIONS,
        resumen = "Obtener una ubicación",
        permission = "location.view",
        parameters = listOf(route("id", integer())),
        responses = listOf(
            Response("200", "Ubicación.", ref("LocationResponse")),
            Response("404", "No existe.", ref("ErrorResponse")),
        ),
        errorCodes = listOf("INVALID_ID", "LOCATION_NOT_FOUND"),
    ),
    Operation(
        method = "PUT",
        route = "/v1/locations/{id}",
        label = TAG_LOCATIONS,
        resumen = "Actualizar una ubicación",
        description = "Mover la ubicación de bodega se rechaza con `409 WAREHOUSE_IMMUTABLE`: las " +
            "etiquetas físicas ya están impresas con su bodega.",
        permission = "location.edit",
        parameters = listOf(route("id", integer())) + clientHeaders,
        body = ref("LocationUpdateRequest"),
        responses = listOf(
            Response("200", "Ubicación actualizada.", ref("LocationResponse")),
            Response("404", "No existe.", ref("ErrorResponse")),
            Response("409", "Bodega inmutable, o la ubicación ya estaba desactivada.", ref("ErrorResponse")),
        ),
        errorCodes = listOf(
            "INVALID_ID", "LOCATION_NOT_FOUND", "INVALID_LEVELS", "INVALID_LOCATION_KIND",
            "INVALID_LOCATION_PART", "UNKNOWN_WAREHOUSE", "DUPLICATE_LOCATION",
            "WAREHOUSE_IMMUTABLE", "LOCATION_ALREADY_INACTIVE",
        ),
    ),
    Operation(
        method = "DELETE",
        route = "/v1/locations/{id}",
        label = TAG_LOCATIONS,
        resumen = "Desactivar una ubicación (borrado LÓGICO)",
        description = "El motivo es OPCIONAL aquí (el contrato solo lo exige para productos), pero " +
            "si viene se registra. La ubicación de RECEPCIÓN está protegida: " +
            "`409 RECEIVING_LOCATION_PROTECTED`. Una ubicación con existencias no se desactiva: " +
            "`409 LOCATION_HAS_STOCK` con `details.stock`.",
        permission = "location.delete",
        parameters = listOf(route("id", integer())) + clientHeaders,
        body = ref("MotivoDeBorradoRequest"),
        responses = listOf(
            Response("204", "Ubicación desactivada. Sin cuerpo."),
            Response("409", "Tiene existencias, es la de recepción, o ya estaba desactivada.", ref("ErrorResponse")),
        ),
        errorCodes = listOf("INVALID_ID", "LOCATION_NOT_FOUND", "LOCATION_HAS_STOCK", "RECEIVING_LOCATION_PROTECTED", "LOCATION_ALREADY_INACTIVE"),
    ),

    // ── Bodegas ─────────────────────────────────────────────────────────────
    Operation(
        method = "GET",
        route = "/v1/warehouses",
        label = TAG_WAREHOUSES,
        resumen = "Listar bodegas (catálogo completo, sin paginar)",
        description = "**Sin sobre de paginación y sin límite de 200**, y es deliberado: son un " +
            "catálogo cerrado y diminuto que la interfaz necesita COMPLETO para poblar un " +
            "desplegable. Lo que lo hace seguro es que exige permiso de administración sobre un " +
            "conjunto que solo crece cuando un administrador lo decide.\n\nEl permiso es " +
            "`location.view`: `CONTRATO_API.md` §11 no lista esta ruta, y dejarla sin permiso " +
            "sería repetir el defecto del legacy (rutas que parecían protegidas y no lo estaban).",
        permission = "location.view",
        parameters = listOf(search(), activeFilter()),
        responses = listOf(Response("200", "Todas las bodegas.", ref("WarehouseListResponse"))),
        errorCodes = listOf("PERMISSION_DENIED", "SCOPE_INSUFFICIENT", "INVALID_FILTER"),
    ),

    // ── Inventario ──────────────────────────────────────────────────────────
    Operation(
        method = "GET",
        route = "/v1/inventory/stock",
        label = TAG_INVENTORY,
        resumen = "Existencias por producto y ubicación",
        description = "Los filtros reales son `productCode`, `locationCode` y `onlyWithStock` " +
            "(por defecto `true`). `totalItems` se CUENTA en la base, no se deduce de `items`: " +
            "antes se pasaba `items.size` y el sobre decía siempre «50 resultados».",
        permission = "inventory.view",
        parameters = pagination(listOf("productCode"), "productCode") + listOf(
            query("productCode", string(example = "ABRAA1B"), "Filtra por producto."),
            query("locationCode", string(example = "04-B-C"), "Filtra por ubicación."),
            query(
                "onlyWithStock",
                boolean("Por defecto `true`.", nullable = true),
                "Si es `true`, omite las filas con existencia cero.",
            ),
        ),
        responses = listOf(Response("200", "Listado paginado de existencias.", pageEnvelope("ExistenciaDto"))),
        errorCodes = listOf("INVALID_PAGINATION", "INVALID_SORT", "INVALID_PARAMETER"),
    ),
    Operation(
        method = "GET",
        route = "/v1/inventory/kardex",
        label = TAG_INVENTORY,
        resumen = "Kardex (libro de movimientos)",
        description = "**El kardex es append-only y no se puede modificar por API**: no existe " +
            "`PUT` ni `DELETE`. Las correcciones son movimientos NUEVOS. El permiso real es " +
            "`kardex.view` (el contrato §11 le asigna `inventory.view`).",
        permission = "kardex.view",
        parameters = pagination(listOf("movedAt", "productCode", "moveType"), "movedAt") + listOf(
            query("productCode", string(example = "ABRAA1B"), "Filtra por producto."),
            query("moveType", string(example = "RECEIPT_IN"), "Filtra por tipo de movimiento."),
            query("from", string(formato = "date"), "Desde (inclusive), `AAAA-MM-DD`."),
            query("to", string(formato = "date"), "Hasta (inclusive), `AAAA-MM-DD`."),
        ),
        responses = listOf(Response("200", "Listado paginado de movimientos.", pageEnvelope("KardexDto"))),
        errorCodes = listOf("INVALID_PAGINATION", "INVALID_SORT", "INVALID_FILTER_DATE", "INVALID_PARAMETER"),
    ),
    Operation(
        method = "POST",
        route = "/v1/inventory/adjustments",
        label = TAG_INVENTORY,
        resumen = "Ajustar existencias — la regla única `AdjustStock`",
        description = "Los cuatro orígenes del legacy se unifican aquí.\n\nEl permiso **depende de " +
            "la dirección y del motivo**: aumentar no es lo mismo que mermar, y un robo no es lo " +
            "mismo que un error de conteo. Un permiso genérico de «ajustar» daría a cualquiera la " +
            "capacidad de tapar un robo con un ajuste que él mismo firma.\n\nEl motivo es " +
            "OBLIGATORIO siempre. Un ajuste por encima del umbral queda en `PENDING_APPROVAL` y " +
            "responde **`202 Accepted`**, no `201`.\n\n`approverId` va en el CUERPO y no se deduce " +
            "del token porque tiene que ser OTRA persona: el dominio rechaza que coincida con " +
            "quien solicita.",
        permission = "inventory.adjust_increase | inventory.adjust_decrease | inventory.adjust_theft | inventory.adjust_damage | inventory.adjust_shortage | inventory.adjust_count_error",
        parameters = listOf(idempotencyHeader) + clientHeaders,
        body = ref("AjusteRequest"),
        responses = listOf(
            Response("201", "Ajuste aplicado.", ref("MovimientoResponse")),
            Response("202", "Ajuste aceptado y PENDIENTE DE APROBACIÓN: `status = PENDING_APPROVAL`.", ref("MovimientoResponse")),
            Response("422", "Falta el motivo o no hay existencia suficiente.", ref("ErrorResponse")),
        ),
        errorCodes = listOf(
            "REASON_REQUIRED", "INSUFFICIENT_STOCK", "SEGREGATION_OF_DUTIES", "INVALID_PARAMETER",
            "INVALID_PRODUCT_CODE", "INVALID_LOCATION_CODE", "FIELD_TOO_LONG",
        ),
    ),
    Operation(
        method = "POST",
        route = "/v1/inventory/locate",
        label = TAG_INVENTORY,
        resumen = "Ubicar mercancía: de recepción al estante",
        description = "**El total NO cambia**: es un traslado. El legacy mutaba el registro de " +
            "recepción del kardex y por eso su libro dejaba de ser un libro.\n\nEl cuerpo real es " +
            "el mismo que el de `move` (`productCode`, `fromLocationCode`, `toLocationCode`, " +
            "`quantity`); NO existe `invoiceLineId`, aunque el contrato lo prometa.\n\nUna línea en " +
            "`DISCREPANCY` no se puede ubicar hasta que se apruebe la factura: `409 " +
            "DISCREPANCY_NOT_APPROVED` (regla B1).",
        permission = "invoice.locate",
        parameters = listOf(idempotencyHeader) + clientHeaders,
        body = ref("TrasladoRequest"),
        responses = listOf(
            Response("201", "Traslado registrado. `total` viaja vacío: el total no cambió.", ref("MovimientoResponse")),
            Response("409", "La línea está en discrepancia sin aprobar.", ref("ErrorResponse")),
            Response("422", "Origen y destino coinciden, o no hay existencia en el origen.", ref("ErrorResponse")),
        ),
        errorCodes = listOf(
            "DISCREPANCY_NOT_APPROVED", "SAME_SOURCE_AND_DESTINATION", "INSUFFICIENT_STOCK",
            "INVALID_PRODUCT_CODE", "INVALID_LOCATION_CODE",
        ),
    ),
    Operation(
        method = "POST",
        route = "/v1/inventory/move",
        label = TAG_INVENTORY,
        resumen = "Mover entre estantes de la misma bodega",
        description = "Mover es una operación DISTINTA de ubicar: ubicar saca de recepción, mover " +
            "reorganiza. El operador tiene la primera pero no necesariamente la segunda.",
        permission = "inventory.move",
        parameters = listOf(idempotencyHeader) + clientHeaders,
        body = ref("TrasladoRequest"),
        responses = listOf(
            Response("201", "Traslado registrado. El total del producto no cambia.", ref("MovimientoResponse")),
            Response("422", "Origen igual a destino, o existencia insuficiente en el origen.", ref("ErrorResponse")),
        ),
        errorCodes = listOf(
            "SAME_SOURCE_AND_DESTINATION", "INSUFFICIENT_STOCK", "INVALID_PRODUCT_CODE", "INVALID_LOCATION_CODE",
        ),
    ),

    // ── Recepción ───────────────────────────────────────────────────────────
    Operation(
        method = "POST",
        route = "/v1/invoices",
        label = TAG_INVOICES,
        resumen = "Crear factura de recepción",
        description = "**No hay `GET` de listado en esta versión**: `CONTRATO_API.md` §5 promete " +
            "`GET|POST /v1/invoices`, pero el listado no está implementado (ver " +
            "`x-contract-divergences`).\n\nLa respuesta de la creación es la REFERENCIA " +
            "(`id` + `invoiceNumber`), no la factura completa: para el detalle hay que pedir " +
            "`GET /v1/invoices/{id}`. Una factura necesita al menos una línea: `400 " +
            "FIELD_REQUIRED` si viene vacía, porque una factura sin líneas no se puede capturar " +
            "ni ubicar y bloquea el flujo.",
        permission = "invoice.create",
        parameters = clientHeaders,
        body = ref("NuevaFacturaRequest"),
        responses = listOf(
            Response("201", "Factura creada.", ref("FacturaCreadaResponse")),
            Response("400", "Falta el número, no hay líneas o la fecha no es `AAAA-MM-DD`.", ref("ErrorResponse")),
        ),
        errorCodes = listOf("FIELD_REQUIRED", "INVALID_PARAMETER", "UNKNOWN_UNIT_MEASURE", "INVALID_PRODUCT_CODE"),
    ),
    Operation(
        method = "GET",
        route = "/v1/invoices/{id}",
        label = TAG_INVOICES,
        resumen = "Detalle de la factura CON sus líneas",
        description = "`difference` **la calcula la base** con una columna generada: es imposible " +
            "que se desincronice. En el legacy estaba declarada y siempre valía 0.",
        permission = "invoice.view",
        parameters = listOf(route("id", integer())),
        responses = listOf(
            Response("200", "Factura con sus líneas.", ref("FacturaDetalle")),
            Response("400", "El id de la ruta no es un número.", ref("ErrorResponse")),
            Response("404", "No existe esa factura.", ref("ErrorResponse")),
        ),
        errorCodes = listOf("INVALID_ID", "NOT_FOUND"),
    ),
    Operation(
        method = "GET",
        route = "/v1/invoices/pending-approval",
        label = TAG_INVOICES,
        resumen = "Bandeja de facturas con líneas por aprobar",
        description = "**Pantalla nueva: el legacy no la tenía**, y sin ella una discrepancia se " +
            "quedaba bloqueada sin que nadie supiera que había que aprobarla. La respuesta NO " +
            "usa el sobre de paginación: es un `{ items }`.",
        permission = "invoice.view",
        responses = listOf(Response("200", "Facturas con líneas en discrepancia.", ref("PendientesResponse"))),
    ),
    Operation(
        method = "POST",
        route = "/v1/invoices/{id}/lines/{lineId}/capture",
        label = TAG_INVOICES,
        resumen = "Capturar el conteo físico de una línea",
        description = "Calcula `difference` y decide el estado: `CAPTURED` si coincide, " +
            "`DISCREPANCY` si no. Devuelve la LÍNEA, no la factura.",
        permission = "invoice.capture",
        parameters = listOf(
            route("id", integer("Identificador de la factura.")),
            route("lineId", integer("Identificador de la línea.")),
            idempotencyHeader,
        ) + clientHeaders,
        body = ref("CapturarLineaRequest"),
        responses = listOf(
            Response("200", "Línea capturada.", ref("LineaDto")),
            Response("400", "Alguno de los identificadores no es un número.", ref("ErrorResponse")),
        ),
        errorCodes = listOf("INVALID_ID", "FIELD_REQUIRED", "INVALID_QUANTITY", "INCONSISTENT_BOX_COUNT", "EXCEEDS_LINE_QUANTITY", "INVOICE_CLOSED"),
    ),
    Operation(
        method = "POST",
        route = "/v1/invoices/{id}/approve",
        label = TAG_INVOICES,
        resumen = "Aprobar las discrepancias de la FACTURA",
        description = "**La ÚNICA ruta de aprobación: no existe una variante por línea** " +
            "(decisión R-86). Una factura llega con varias líneas en discrepancia, y aprobarlas " +
            "de una en una obliga al supervisor a repetir el mismo juicio N veces y deja el " +
            "documento en un estado intermedio. Aprobar por línea era además lo que invitaba a " +
            "aprobar lo propio sin darse cuenta.\n\n`422 SEGREGATION_OF_DUTIES` si quien aprueba " +
            "capturó ALGUNA de las líneas pendientes; el mensaje indica CUÁLES para que el " +
            "supervisor sepa qué reasignar. La base impone la regla con un `CHECK`: es imposible " +
            "que una línea llegue a `APPROVED` sin `approved_by`.\n\nSi no hay nada pendiente, el " +
            "código real es `NOT_PENDING_APPROVAL` (el contrato dice `NO_PENDING_APPROVAL`).",
        permission = "invoice.approve",
        parameters = listOf(route("id", integer())) + clientHeaders,
        body = ref("AprobarFacturaRequest"),
        responses = listOf(
            Response("200", "Factura completa con sus líneas ya en `APPROVED`.", ref("FacturaDetalle")),
            Response("409", "No hay ninguna línea pendiente de aprobar.", ref("ErrorResponse")),
            Response("422", "Falta el motivo, o quien aprueba capturó alguna línea pendiente.", ref("ErrorResponse")),
        ),
        errorCodes = listOf("REASON_REQUIRED", "SEGREGATION_OF_DUTIES", "NOT_PENDING_APPROVAL", "INVALID_ID"),
    ),

    // ── Identidad ───────────────────────────────────────────────────────────
    Operation(
        method = "GET",
        route = "/v1/users",
        label = TAG_IDENTITY,
        resumen = "Listar usuarios",
        permission = "user.view",
        parameters = pagination(listOf("username", "name", "createdAt"), "username") +
            listOf(search(), activeFilter()),
        responses = listOf(Response("200", "Listado paginado.", pageEnvelope("UsuarioAdmin"))),
        errorCodes = listOf("INVALID_PAGINATION", "INVALID_SORT", "INVALID_FILTER"),
    ),
    Operation(
        method = "GET",
        route = "/v1/roles",
        label = TAG_IDENTITY,
        resumen = "Listar roles con su número de permisos",
        permission = "role.manage",
        parameters = pagination(listOf("key", "name", "sortOrder"), "sortOrder") +
            listOf(search(), activeFilter()),
        responses = listOf(Response("200", "Listado paginado.", pageEnvelope("RolAdmin"))),
        errorCodes = listOf("INVALID_PAGINATION", "INVALID_SORT", "INVALID_FILTER"),
    ),
    Operation(
        method = "GET",
        route = "/v1/permissions",
        label = TAG_IDENTITY,
        resumen = "Catálogo de permisos (agrupado y plano)",
        description = "Llega ENTERO, sin paginar: es la matriz de la pantalla de roles y el cliente " +
            "tendría que pedir todas las páginas antes de poder dibujar nada. Viaja agrupado por " +
            "módulo (`modules`) y también plano (`items`), porque el dato es el mismo y un " +
            "selector de permisos no tiene por qué recorrer los módulos.",
        permission = "role.manage",
        responses = listOf(Response("200", "Catálogo completo de permisos.", ref("CatalogoDePermisosResponse"))),
    ),

    // ── Auditoría ───────────────────────────────────────────────────────────
    Operation(
        method = "GET",
        route = "/v1/audit/log",
        label = TAG_AUDIT,
        resumen = "Registro de auditoría (solo lectura)",
        description = "El orden por defecto es **DESCENDENTE** (`occurredAt,desc`): un registro de " +
            "auditoría se lee de lo último hacia atrás, porque la pregunta casi siempre es «¿qué " +
            "acaba de pasar?». El listado y el conteo comparten los filtros, así que el sobre no " +
            "puede decir una cosa y los items otra.\n\nUn filtro mal escrito se RECHAZA con 400, " +
            "no se ignora: ignorarlo haría que el cliente pidiera «las denegaciones de ayer» y " +
            "recibiera otra cosa sin saberlo.",
        permission = "audit.view",
        parameters = pagination(listOf("occurredAt"), "occurredAt,desc") + listOf(
            query("from", string(formato = "date-time"), "Desde, ISO-8601."),
            query("to", string(formato = "date-time"), "Hasta, ISO-8601. Rango invertido: `400 INVALID_FILTER_RANGE`."),
            query("userId", integer(), "Actor."),
            query("entity", string(), "Tipo de entidad."),
            query("entityId", string(), "Identificador de la entidad."),
            query("action", string(), "Acción registrada."),
            query("correlationId", string(formato = "uuid"), "Correlación con `X-Request-Id`."),
            query("decision", string(), "Decisión de autorización."),
            query("result", string(), "Resultado."),
            query("actionLevel", string(), "Nivel de la acción."),
            query("channel", string(enumeration = listOf("WEB", "MOBILE", "MOBILE_OFFLINE", "SERVICE_PRINTER")), "Canal."),
        ),
        responses = listOf(Response("200", "Listado paginado.", pageEnvelope("AuditEntryDto"))),
        errorCodes = listOf("INVALID_PAGINATION", "INVALID_SORT", "INVALID_FILTER", "INVALID_FILTER_RANGE", "INVALID_FILTER_DATE"),
    ),

    // ── Reportes ────────────────────────────────────────────────────────────
    Operation(
        method = "GET",
        route = "/v1/reports/kardex-vs-projection",
        label = TAG_REPORTS,
        resumen = "Reconciliación: ledger contra proyección de existencias",
        description = "Compara lo que dice el ledger con lo que dicen las proyecciones. En el " +
            "legacy esta comprobación no existía y el descuadre solo se descubría con un conteo " +
            "físico.\n\n**La respuesta NO es la que promete `CONTRATO_API.md` §9**: devuelve " +
            "`{ checkedAt, cuadra, divergences, items }`, sin `productsChecked` ni `ledgerNet`; " +
            "`items` trae SOLO los productos descuadrados, con `totalEnBd`, `totalEsperado` y " +
            "`diferencia`. `cuadra: true` con `items` vacío es el estado sano.\n\nEl permiso real " +
            "es `report.view` y la ruta no aparece en la tabla §11.",
        permission = "report.view",
        responses = listOf(Response("200", "Informe de reconciliación.", ref("ReporteReconciliacion"))),
    ),

    // ── Salud ───────────────────────────────────────────────────────────────
    Operation(
        method = "GET",
        route = "/v1/health",
        label = TAG_HEALTH,
        resumen = "Estado del servicio",
        description = "**Público, sin autenticación.** Responde `503` cuando el estado es `DOWN`. " +
            "`schemaVersion` es información de DIAGNÓSTICO: si no se puede leer, el estado sigue " +
            "siendo `UP` y el motivo viaja en `database.error`.",
        security = Security.PUBLICA,
        responses = listOf(
            Response("200", "Servicio operativo.", ref("HealthResponse")),
            Response("503", "Alguna dependencia está caída.", ref("HealthResponse")),
        ),
    ),
)
