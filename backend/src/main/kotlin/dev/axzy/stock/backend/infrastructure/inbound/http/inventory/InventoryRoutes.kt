package dev.axzy.stock.backend.infrastructure.inbound.http.inventory

import dev.axzy.stock.backend.application.inventory.AdjustmentRequestDto
import dev.axzy.stock.backend.application.inventory.AdjustmentStatus
import dev.axzy.stock.backend.application.inventory.InventoryUseCases
import dev.axzy.stock.backend.application.inventory.KardexFilter
import dev.axzy.stock.backend.application.inventory.MovementResponse
import dev.axzy.stock.backend.application.inventory.ReconciliationReport
import dev.axzy.stock.backend.application.inventory.StockFilter
import dev.axzy.stock.backend.application.inventory.StockMoveRequestDto
import dev.axzy.stock.backend.application.inventory.adjustmentPermission
import dev.axzy.stock.backend.application.shared.Page
import dev.axzy.stock.backend.application.shared.toResponse
import dev.axzy.stock.backend.application.shared.unitsText
import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.pageRequest
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.requirePermission
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.requireUser
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
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Rutas de inventario (contrato §4 y §9).
 *
 * **Ninguna ruta mueve stock por su cuenta**: todas delegan en [InventoryUseCases].
 *
 * El kardex es **append-only**: no hay `PUT` ni `DELETE`. Las correcciones son movimientos
 * nuevos, y esa restricción se expresa no escribiendo esas rutas.
 */
fun Route.inventoryRoutes(
    useCases: InventoryUseCases,
    permissionChecker: PermissionChecker,
) {

    post("/v1/inventory/adjustments") {
        val body = call.receive<AdjustmentRequestDto>()

        val direction = enumValueOrNull<AdjustmentDirection>(body.direction)
            ?: throw BadRequestException(
                "INVALID_PARAMETER",
                "La dirección debe ser INCREASE o DECREASE.",
                mapOf("allowed" to "INCREASE, DECREASE"),
            )
        val reason = enumValueOrNull<AdjustmentReason>(body.reason)
            ?: throw BadRequestException(
                "INVALID_PARAMETER",
                "El motivo no es válido.",
                mapOf("allowed" to AdjustmentReason.entries.joinToString(", ") { it.name }),
            )
        val origin = enumValueOrNull<AdjustmentOrigin>(body.origin)
            ?: throw BadRequestException(
                "INVALID_PARAMETER",
                "El origen no es válido.",
                mapOf("allowed" to AdjustmentOrigin.entries.joinToString(", ") { it.name }),
            )

        // El permiso depende de la DIRECCIÓN y del MOTIVO, y por eso se comprueba después de
        // leer el cuerpo: aumentar no es lo mismo que mermar, y un robo no es un error de conteo.
        call.requirePermission(permissionChecker, adjustmentPermission(direction, reason))

        val product = ProductCode.validate(body.productCode).getOrNull()
            ?: throw BadRequestException(
                "INVALID_PRODUCT_CODE",
                "El código de producto '${body.productCode}' no es válido.",
            )
        val location = LocationCode.validate(body.locationCode).getOrNull()
            ?: throw BadRequestException(
                "INVALID_LOCATION_CODE",
                "La ubicación '${body.locationCode}' no es válida.",
            )

        // Quien solicita sale del token; quien aprueba, del CUERPO, porque tiene que ser otra
        // persona y el dominio lo comprueba.
        val requester = call.requireUser()

        val request = when (
            val created = AdjustmentRequest.create(
                productCode = product,
                locationCode = location,
                quantity = quantityOf(body.quantity),
                direction = direction,
                reason = reason,
                origin = origin,
                note = body.note,
                requestedBy = requester,
                idempotencyKey = call.idempotencyKey(),
            )
        ) {
            is Either.Left -> throw UnprocessableException(created.error.code, created.error.message)
            is Either.Right -> created.value
        }

        val result = useCases.applyAdjustment(
            request = request,
            approverId = body.approverId,
            ip = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim(),
        )

        call.respond(
            if (result.status == AdjustmentStatus.PENDING_APPROVAL) HttpStatusCode.Accepted else HttpStatusCode.Created,
            MovementResponse(
                kardexIds = result.movements,
                status = result.status.name,
                total = result.total.unitsText(),
                message = result.reason,
            ),
        )
    }

    post("/v1/inventory/locate") {
        // Mismo permiso que ubicar una línea de factura: es el mismo acto físico.
        call.requirePermission(permissionChecker, "invoice.locate")
        call.moveStock(useCases)
    }

    post("/v1/inventory/move") {
        // Mover entre estantes no es ubicar: ubicar saca de recepción, mover reorganiza. El
        // operador tiene lo primero pero no necesariamente lo segundo.
        call.requirePermission(permissionChecker, "inventory.move")
        call.moveStock(useCases)
    }

    get("/v1/inventory/stock") {
        call.requirePermission(permissionChecker, "inventory.view")

        // Los campos ordenables se declaran POR ENDPOINT. `quantity` no está: ordenar por saldo
        // exigiría un índice que no existe para un reporte que se lee de a poco.
        val page = call.pageRequest(fieldsSortable = setOf("productCode"), defaultOrder = "productCode")
        val filter = StockFilter(
            productCode = call.request.queryParameters["productCode"],
            locationCode = call.request.queryParameters["locationCode"],
            onlyWithStock = call.request.queryParameters["onlyWithStock"]?.toBoolean() ?: true,
        )

        val items = useCases.stockByLocation(filter, offset = page.offset.toInt(), limit = page.size)
        val total = useCases.countStock(filter)

        call.respond(HttpStatusCode.OK, Page(items, page.page, page.size, total).toResponse { it })
    }

    get("/v1/inventory/kardex") {
        call.requirePermission(permissionChecker, "kardex.view")

        val page = call.pageRequest(
            fieldsSortable = setOf("movedAt", "productCode", "moveType"),
            defaultOrder = "movedAt",
        )
        val filter = KardexFilter(
            productCode = call.request.queryParameters["productCode"],
            type = call.request.queryParameters["moveType"],
            from = call.request.queryParameters["from"]?.let { runCatching { Instant.parse("${it}T00:00:00Z") }.getOrNull() },
            until = call.request.queryParameters["to"]?.let { runCatching { Instant.parse("${it}T23:59:59Z") }.getOrNull() },
        )

        val items = useCases.kardex(filter, offset = page.offset.toInt(), limit = page.size)
        val total = useCases.countKardex(filter)

        call.respond(HttpStatusCode.OK, Page(items, page.page, page.size, total).toResponse { it })
    }

    get("/v1/reports/kardex-vs-projection") {
        // `report.view` y no `inventory.reconcile`: la web protege la pantalla `/reports` con
        // `report.view`, y con otro permiso aquí un usuario entraba a la pantalla y recibía un
        // 403 al cargar. El permiso de una ruta tiene que ser el del guard de su pantalla.
        call.requirePermission(permissionChecker, "report.view")

        val divergences = useCases.divergences()

        call.respond(
            HttpStatusCode.OK,
            ReconciliationReport(
                checkedAt = Instant.now().toString(),
                balanced = divergences.isEmpty(),
                divergences = divergences.size,
                items = divergences,
            ),
        )
    }
}

/** Cuerpo compartido por ubicar y mover. */
private suspend fun ApplicationCall.moveStock(useCases: InventoryUseCases) {
    val body = receive<StockMoveRequestDto>()

    val product = ProductCode.validate(body.productCode).getOrNull()
        ?: throw BadRequestException("INVALID_PRODUCT_CODE", "El código de producto no es válido.")
    val from = LocationCode.validate(body.fromLocationCode).getOrNull()
        ?: throw BadRequestException("INVALID_LOCATION_CODE", "La ubicación de origen no es válida.")
    val until = LocationCode.validate(body.toLocationCode).getOrNull()
        ?: throw BadRequestException("INVALID_LOCATION_CODE", "La ubicación de destino no es válida.")

    val kardexId = useCases.locate(
        productCode = product,
        from = from,
        until = until,
        quantity = quantityOf(body.quantity),
        userId = requireUser(),
        key = idempotencyKey(),
    )

    respond(
        HttpStatusCode.Created,
        MovementResponse(
            kardexIds = listOf(kardexId),
            status = AdjustmentStatus.APPLIED.name,
            // El total NO cambió: es un traslado. El mensaje evita que el cliente crea que el
            // movimiento no hizo nada.
            total = "",
            message = "Traslado registrado. El total del producto no cambia: es un " +
                "movimiento entre ubicaciones, no una entrada ni una salida.",
        ),
    )
}

/** Traduce la cantidad del contrato (3 decimales) a la del dominio. */
private fun quantityOf(value: Double): Quantity =
    Quantity.ofScaled(BigDecimal.valueOf(value).movePointRight(Quantity.SCALE).toLong())

private inline fun <reified E : Enum<E>> enumValueOrNull(raw: String): E? =
    runCatching { enumValueOf<E>(raw.uppercase()) }.getOrNull()

/**
 * La clave de idempotencia de la petición, validada como UUID. `null` si no viene, que es
 * válido: significa «no soy un reintento».
 *
 * Se valida aquí y no se deja al `?::uuid` de PostgreSQL: su fallo saldría como **500** cuando
 * el que se equivocó fue quien llamó, y dejaría en los registros un incidente que no existe.
 */
private fun ApplicationCall.idempotencyKey(): String? {
    val raw = request.headers["Idempotency-Key"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return try {
        UUID.fromString(raw).toString()
    } catch (_: IllegalArgumentException) {
        throw BadRequestException(
            "INVALID_IDEMPOTENCY_KEY",
            "La cabecera 'Idempotency-Key' debe ser un UUID. Se recibió '$raw'.",
            mapOf("header" to "Idempotency-Key"),
        )
    }
}
