package dev.axzy.stock.backend.inventory

import dev.axzy.stock.backend.catalog.Page
import dev.axzy.stock.backend.catalog.PageRequest
import dev.axzy.stock.backend.catalog.aResponse
import dev.axzy.stock.backend.inventory.InventoryService
import dev.axzy.stock.backend.security.PermissionDecision
import dev.axzy.stock.backend.security.userFromToken
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.inventory.AdjustmentDirection
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.AdjustmentRequest
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RUTAS DE INVENTARIO
 *
 * Los seis endpoints de `CONTRATO_API.md` §4 y §9.
 *
 * **Ninguna ruta mueve stock por su cuenta.** Todas delegan en [InventoryService],
 * que a su vez pasa por el motor SQL. Es lo que garantiza que las tres cosas que
 * componen un movimiento —ajustar ubicaciones, recalcular el total y escribir el
 * kardex— ocurran siempre juntas y en una transacción.
 *
 * El kardex es **append-only**: no hay `PUT` ni `DELETE`. Las correcciones son
 * movimientos nuevos, y esa restricción se expresa no escribiendo esas rutas.
 * ═══════════════════════════════════════════════════════════════════════════
 */

@Serializable
data class AdjustmentRequestDto(
    val productCode: String,
    val locationCode: String,
    /** Número decimal con 3 decimales. La dirección indica si suma o resta. */
    val quantity: Double,
    /** `INCREASE` o `DECREASE`. */
    val direction: String,
    val reason: String,
    val origin: String,
    val note: String? = null,
    /**
     * Quién aprueba, si el ajuste lo requiere.
     *
     * Va en el cuerpo y no se deduce del token porque **tiene que ser otra
     * persona**: el dominio rechaza que coincida con quien solicita, y eso es la
     * segregación de funciones.
     */
    val approverId: Long? = null,
)

@Serializable
data class TransferRequestDto(
    val productCode: String,
    val fromLocationCode: String,
    val toLocationCode: String,
    val quantity: Double,
)

@Serializable
data class MovementResponse(
    val kardexIds: List<Long>,
    val status: String,
    val total: String,
    val message: String? = null,
)

fun Route.inventoryRoutes(
    service: InventoryService,
    permissionChecker: PermissionChecker,
) {

    /** Comprueba el permiso y corta con el código correcto si no lo tiene. */
    suspend fun require(
        call: io.ktor.server.application.ApplicationCall,
        permission: String,
    ) {
        when (val decision = permissionChecker.check(call, permission)) {
            is PermissionDecision.Permitted -> Unit
            is PermissionDecision.Denied ->
                throw dev.axzy.stock.backend.error.ForbiddenException(decision.code, decision.message)
        }
    }

    /** Traduce la cantidad del contrato (3 decimales) a la del dominio. */
    fun quantity(value: Double): Quantity =
        Quantity.ofScaled(java.math.BigDecimal.valueOf(value).movePointRight(3).toLong())

    // ═══════════════════════════════════════════════════════════════════════
    // POST /v1/inventory/adjustments — la regla única
    // ═══════════════════════════════════════════════════════════════════════
    post("/v1/inventory/adjustments") {
        val body = call.receive<AdjustmentRequestDto>()

        val direction = runCatching { AdjustmentDirection.valueOf(body.direction.uppercase()) }
            .getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_PARAMETER",
                "La dirección debe ser INCREASE o DECREASE.",
                mapOf("allowed" to "INCREASE, DECREASE"),
            )

        val reason = runCatching { AdjustmentReason.valueOf(body.reason.uppercase()) }
            .getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_PARAMETER",
                "El motivo no es válido.",
                mapOf("allowed" to AdjustmentReason.entries.joinToString(", ") { it.name }),
            )

        val origin = runCatching { AdjustmentOrigin.valueOf(body.origin.uppercase()) }
            .getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_PARAMETER",
                "El origen no es válido.",
                mapOf("allowed" to AdjustmentOrigin.entries.joinToString(", ") { it.name }),
            )

        // El permiso depende de la DIRECCIÓN y del MOTIVO: aumentar no es lo mismo
        // que mermar, y un robo no es lo mismo que un error de conteo. Un permiso
        // genérico de «ajustar» daría a cualquiera la capacidad de tapar un robo.
        val permission = adjustmentPermission(direction, reason)
        require(call, permission)

        val product = ProductCode.validate(body.productCode).getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_PRODUCT_CODE",
                "El código de producto '${body.productCode}' no es válido.",
            )

        val location = LocationCode.validate(body.locationCode).getOrNull()
            ?: throw dev.axzy.stock.backend.error.BadRequestException(
                "INVALID_LOCATION_CODE",
                "La ubicación '${body.locationCode}' no es válida.",
            )

        // El usuario sale del token; el aprobador del CUERPO, porque tiene que ser
        // otra persona y el dominio lo comprueba.
        val requester = call.userFromToken() ?: throw dev.axzy.stock.backend.error.UnauthorizedException()

        val request = when (
            val result = AdjustmentRequest.create(
                productCode = product,
                locationCode = location,
                quantity = quantity(body.quantity),
                direction = direction,
                reason = reason,
                origin = origin,
                note = body.note,
                requestedBy = requester,
                // **La clave se valida aquí y no se deja al CAST de PostgreSQL.**
                // `?::uuid` con un valor que no es UUID lanza una excepción de
                // PostgreSQL que sale como **500 INTERNAL_ERROR**, cuando el problema
                // es del cliente y merece un **400**. Lo encontré pasando
                // `Idempotency-Key: v-SINUBICAR-...` en una prueba mía.
                //
                // Un 500 es una mentira: dice «el servidor falló» cuando el servidor
                // está bien y el que se equivocó fue quien llamó. Y en los registros
                // deja un incidente que no existe.
                idempotencyKey = idempotencyKey(call),
            )
        ) {
            is Either.Left -> throw dev.axzy.stock.backend.error.UnprocessableException(
                result.error.code,
                result.error.message,
            )
            is Either.Right -> result.value
        }

        val result = service.applyAdjustment(
            request = request,
            approverId = body.approverId,
            ip = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim(),
        )

        val code = if (result.status == "PENDING_APPROVAL") {
            HttpStatusCode.Accepted
        } else {
            HttpStatusCode.Created
        }

        call.respond(
            code,
            MovementResponse(
                kardexIds = result.movements,
                status = result.status,
                total = result.total.unitsText(),
                message = result.reason,
            ),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POST /v1/inventory/locate y /v1/inventory/move
    // ═══════════════════════════════════════════════════════════════════════
    post("/v1/inventory/locate") {
        // Mismo permiso que ubicar una línea de factura: es el mismo acto físico.
        require(call, "invoice.locate")
        handleTransfer(call, service, ::quantity)
    }

    post("/v1/inventory/move") {
        // Mover entre estantes es una operación distinta de ubicar: ubicar saca de
        // recepción, mover reorganiza. El operador tiene la primera pero no
        // necesariamente la segunda.
        require(call, "inventory.move")
        handleTransfer(call, service, ::quantity)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/inventory/stock
    // ═══════════════════════════════════════════════════════════════════════
    get("/v1/inventory/stock") {
        require(call, "inventory.view")

        // Los campos ordenables se declaran POR ENDPOINT: el contrato lo exige, y
        // `quantity` no está porque ordenar por saldo en la base exigiría un índice
        // que no existe para un reporte que se lee de a poco.
        val page = call.page(fieldsSortable = setOf("productCode"), defaultOrder = "productCode")

        val items = service.stockByLocation(
            productCode = call.request.queryParameters["productCode"],
            locationCode = call.request.queryParameters["locationCode"],
            onlyWithStock = call.request.queryParameters["onlyWithStock"]?.toBoolean() ?: true,
            offset = page.offset.toInt(),
            limit = page.size,
        )

        // **EL TOTAL SE CUENTA, NO SE ADIVINA.** Antes se pasaba `items.size`, así
        // que el sobre decía «50 resultados» siempre y el cliente no podía pintar
        // «1.064 existencias, 22 páginas». Lo encontró un agente al conectar la
        // pantalla de inventario.
        val total = service.countStock(
            productCode = call.request.queryParameters["productCode"],
            locationCode = call.request.queryParameters["locationCode"],
            onlyWithStock = call.request.queryParameters["onlyWithStock"]?.toBoolean() ?: true,
        )

        call.respond(
            HttpStatusCode.OK,
            Page(items, page.page, page.size, total).aResponse { it },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/inventory/kardex
    // ═══════════════════════════════════════════════════════════════════════
    get("/v1/inventory/kardex") {
        require(call, "kardex.view")

        val page = call.page(
            fieldsSortable = setOf("movedAt", "productCode", "moveType"),
            defaultOrder = "movedAt",
        )
        val product = call.request.queryParameters["productCode"]
        val type = call.request.queryParameters["moveType"]
        val from = call.request.queryParameters["from"]?.let { runCatching { Instant.parse("${it}T00:00:00Z") }.getOrNull() }
        val until = call.request.queryParameters["to"]?.let { runCatching { Instant.parse("${it}T23:59:59Z") }.getOrNull() }

        val items = service.kardex(
            productCode = product,
            from = from,
            until = until,
            type = type,
            offset = page.offset.toInt(),
            limit = page.size,
        )

        val total = service.countKardex(productCode = product, type = type, from = from, until = until)

        call.respond(HttpStatusCode.OK, Page(items, page.page, page.size, total).aResponse { it })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/reports/kardex-vs-projection — la reconciliación
    // ═══════════════════════════════════════════════════════════════════════
    get("/v1/reports/kardex-vs-projection") {
        // **`report.view` Y NO `inventory.reconcile`, y era un desajuste real.**
        //
        // La ruta vive bajo `/v1/reports/`, y la web protege la pantalla
        // `/reports` con `report.view`. Con `inventory.reconcile` aquí, **un
        // usuario con permiso de reportes pero sin el de inventario entraba a la
        // pantalla y recibía un 403**, que es la peor combinación: la ve, y falla al
        // cargar. Lo encontró un agente al conectar la pantalla.
        //
        // La regla que se aplica: **el permiso de una ruta tiene que ser el mismo que
        // el del guard que protege su pantalla.** Dos listas de permisos que deben
        // coincidir acaban separándose, y el síntoma aparece solo en producción con
        // un rol concreto.
        //
        // Si en el futuro se quiere restringir la reconciliación a ciertos roles, se
        // crea un reporte aparte o se comprueba el rol explícitamente; no se cambia
        // este permiso a uno que la pantalla no pide.
        require(call, "report.view")

        val divergences = service.divergences()

        call.respond(HttpStatusCode.OK, ReconciliationReport(
            checkedAt = Instant.now().toString(),
            // Cero divergencias es el estado sano, y el reporte lo dice con un
            // booleano explícito en lugar de obligar a contar el arreglo.
            cuadra = divergences.isEmpty(),
            divergences = divergences.size,
            items = divergences,
        ))
    }
}

@Serializable
data class ReconciliationReport(
    val checkedAt: String,
    val cuadra: Boolean,
    val divergences: Int,
    val items: List<dev.axzy.stock.backend.inventory.DivergenceDto>,
)

/**
 * El permiso que exige un ajuste, según su dirección y su motivo.
 *
 * **No hay un permiso genérico de «ajustar», y es deliberado.** Aumentar el
 * inventario no es lo mismo que mermarlo, y un robo no es lo mismo que un error de
 * conteo. Un permiso único daría a cualquiera la capacidad de tapar un robo con un
 * ajuste que él mismo firma.
 */
private fun adjustmentPermission(
    direction: AdjustmentDirection,
    reason: AdjustmentReason,
): String = when {
    direction == AdjustmentDirection.INCREASE -> "inventory.adjust_increase"
    reason == AdjustmentReason.THEFT -> "inventory.adjust_theft"
    reason == AdjustmentReason.DAMAGE -> "inventory.adjust_damage"
    reason == AdjustmentReason.SHORTAGE -> "inventory.adjust_shortage"
    else -> "inventory.adjust_count_error"
}

/** Cuerpo compartido por ubicar y mover. */
private suspend fun handleTransfer(
    call: io.ktor.server.application.ApplicationCall,
    service: InventoryService,
    quantity: (Double) -> Quantity,
) {
    val body = call.receive<TransferRequestDto>()

    val product = ProductCode.validate(body.productCode).getOrNull()
        ?: throw dev.axzy.stock.backend.error.BadRequestException(
            "INVALID_PRODUCT_CODE",
            "El código de producto no es válido.",
        )

    val from = LocationCode.validate(body.fromLocationCode).getOrNull()
        ?: throw dev.axzy.stock.backend.error.BadRequestException(
            "INVALID_LOCATION_CODE",
            "La ubicación de origen no es válida.",
        )

    val until = LocationCode.validate(body.toLocationCode).getOrNull()
        ?: throw dev.axzy.stock.backend.error.BadRequestException(
            "INVALID_LOCATION_CODE",
            "La ubicación de destino no es válida.",
        )

    val user = call.userFromToken() ?: throw dev.axzy.stock.backend.error.UnauthorizedException()

    val kardexId = service.locate(
        productCode = product,
        from = from,
        until = until,
        quantity = quantity(body.quantity),
        userId = user,
        key = idempotencyKey(call),
    )

    call.respond(
        HttpStatusCode.Created,
        MovementResponse(
            kardexIds = listOf(kardexId),
            status = "APPLIED",
            // El total NO cambió: es un traslado. Se devuelve el mensaje para que
            // el cliente no crea que el movimiento no hizo nada.
            total = "",
            message = "Traslado registrado. El total del producto no cambia: es un " +
                "movimiento entre ubicaciones, no una entrada ni una salida.",
        ),
    )
}

/** La cantidad como texto con 3 decimales, que es el formato del contrato. */
private fun Quantity.unitsText(): String =
    java.math.BigDecimal.valueOf(this.value).movePointLeft(3).toPlainString()

/**
 * La paginación de la petición, validada contra los campos que admite el endpoint.
 *
 * Se copia el patrón del catálogo en lugar de inventar otro: `page`, `size` y
 * `sort` se comportan igual en todos los listados, y un `size` mayor de 200 se
 * **rechaza** en vez de recortarse — recortar hace que el cliente crea que tiene
 * todos los datos.
 */
private fun io.ktor.server.application.ApplicationCall.page(
    fieldsSortable: Set<String>,
    defaultOrder: String,
): PageRequest = PageRequest.analyze(
    page = request.queryParameters["page"],
    size = request.queryParameters["size"],
    sort = request.queryParameters["sort"],
    fieldsSortable = fieldsSortable,
    defaultOrder = defaultOrder,
)

/**
 * La clave de idempotencia de la petición, validada como UUID.
 *
 * `null` si no viene, que es válido: significa «no soy un reintento». Si viene y no
 * es un UUID, se rechaza con **400** en lugar de dejar que PostgreSQL lance un error
 * que saldría como **500**.
 */
private fun idempotencyKey(
    call: io.ktor.server.application.ApplicationCall,
): String? {
    val raw = call.request.headers["Idempotency-Key"]?.trim() ?: return null
    if (raw.isEmpty()) return null

    return try {
        java.util.UUID.fromString(raw).toString()
    } catch (_: IllegalArgumentException) {
        throw dev.axzy.stock.backend.error.BadRequestException(
            "INVALID_IDEMPOTENCY_KEY",
            "La cabecera 'Idempotency-Key' debe ser un UUID. Se recibió '$raw'.",
            mapOf("header" to "Idempotency-Key"),
        )
    }
}
