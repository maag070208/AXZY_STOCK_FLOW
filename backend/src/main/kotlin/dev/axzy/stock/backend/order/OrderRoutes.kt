package dev.axzy.stock.backend.order

import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
import dev.axzy.stock.backend.security.userFromToken
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import javax.sql.DataSource

/**
 * Rutas de surtido (contrato §6).
 *
 * El detalle (`/{id}`) es el ÚNICO sitio que devuelve las líneas: el contrato lo
 * justifica porque «la pantalla de empaque no puede funcionar sin ellas».
 */
fun Route.orderRoutes(
    dataSource: DataSource,
    permissionChecker: PermissionChecker,
) {
    val service = OrderService(dataSource)

    route("/v1/orders") {

        get {
            call.requireOrderPermission(permissionChecker, "order.view")

            val page = call.orderPage()
            val status = call.request.queryParameters["status"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (status != null && status !in ORDER_STATUSES) {
                throw BadRequestException(
                    code = "INVALID_PARAMETER",
                    message = "El parámetro 'status' no admite el valor '$status'.",
                    details = mapOf("status" to status, "allowed" to ORDER_STATUSES.joinToString(", ")),
                )
            }
            val search = call.request.queryParameters["q"]

            val items = service.list(status, search, page.offset, page.size)
            val total = service.count(status, search)

            call.respond(
                HttpStatusCode.OK,
                OrderPageResponse(
                    items = items,
                    page = page.page,
                    size = page.size,
                    totalItems = total,
                    totalPages = if (page.size == 0) 0 else ((total + page.size - 1) / page.size).toInt(),
                ),
            )
        }

        post {
            call.requireOrderPermission(permissionChecker, "order.create")
            val request = call.receive<OrderCreateRequest>()
            call.respond(HttpStatusCode.Created, service.create(request, call.orderUser()))
        }

        get("/{id}") {
            call.requireOrderPermission(permissionChecker, "order.view")
            val id = call.orderId()
            val detalle = service.byId(id)
                ?: throw dev.axzy.stock.backend.error.NotFoundException(
                    "La orden $id no existe.",
                    "ORDER_NOT_FOUND",
                )
            call.respond(HttpStatusCode.OK, detalle)
        }

        // ── Empacar: descuenta stock (§6) ───────────────────────────────────
        post("/{id}/lines/{lineId}/package") {
            call.requireOrderPermission(permissionChecker, "order.package")
            val id = call.orderId()
            val lineId = call.parameters["lineId"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador de la línea no es un número.")
            val request = call.receive<PackageLineRequest>()
            call.respond(HttpStatusCode.OK, service.packageLine(id, lineId, request, call.orderUser()))
        }

        // ── Confirmar: descuenta lo que FALTA y exporta ─────────────────────
        post("/{id}/confirm") {
            call.requireOrderPermission(permissionChecker, "order.confirm")
            val id = call.orderId()
            call.respond(HttpStatusCode.OK, service.confirm(id, call.orderUser()))
        }

        // ── Exportar: reintento manual, idempotente ────────────────────────
        post("/{id}/export") {
            // `order.convert` y no `order.confirm`: exportar manda los datos al
            // ERP, que es una acción sensible y de alcance `ALL` en el catálogo.
            call.requireOrderPermission(permissionChecker, "order.convert")
            val id = call.orderId()
            call.respond(HttpStatusCode.OK, service.export(id))
        }

        // ── Faltante: delega en la regla ÚNICA de ajuste ───────────────────
        post("/{id}/shortage") {
            call.requireOrderPermission(permissionChecker, "order.package")
            val id = call.orderId()
            val request = call.receive<OrderShortageRequest>()
            call.respond(HttpStatusCode.OK, service.shortage(id, request, call.orderUser()))
        }
    }
}

/** Los estados de una orden, para validar el filtro contra el enum real. */
private val ORDER_STATUSES = listOf(
    "PENDING", "IN_PROGRESS", "PACKAGED", "PENDING_TO_OUT", "COMPLETED", "CANCELLED",
)

private data class OrderPage(val page: Int, val size: Int) {
    val offset: Int get() = page * size
}

private fun ApplicationCall.orderPage(): OrderPage {
    val brutoPagina = request.queryParameters["page"]
    val brutoTamano = request.queryParameters["size"]

    val pagina = brutoPagina?.trim()?.toIntOrNull()
        ?: if (brutoPagina.isNullOrBlank()) 0 else throw orderPaginationError("page", brutoPagina)
    val tamano = brutoTamano?.trim()?.toIntOrNull()
        ?: if (brutoTamano.isNullOrBlank()) 50 else throw orderPaginationError("size", brutoTamano)

    if (pagina < 0) throw orderPaginationError("page", brutoPagina)
    // Se RECHAZA en lugar de recortar: recortar hace creer al cliente que tiene
    // todos los datos (§1.4).
    if (tamano < 1 || tamano > 200) throw orderPaginationError("size", brutoTamano)

    return OrderPage(pagina, tamano)
}

private fun orderPaginationError(parametro: String, valor: String?) = BadRequestException(
    code = "INVALID_PAGINATION",
    message = "El parámetro '$parametro' no es válido. Se recibió '$valor'. " +
        "`page` empieza en 0 y `size` admite de 1 a 200.",
    details = mapOf(parametro to (valor ?: "")),
)

private fun ApplicationCall.orderId(): Long =
    parameters["id"]?.toLongOrNull()
        ?: throw BadRequestException("INVALID_ID", "El identificador de la orden no es un número.")

/**
 * Quién ejecuta la acción, **leído del token**.
 *
 * Un `?userId=` lo escribiría cualquiera, y el responsable de una salida de
 * inventario es un dato de auditoría: si el cliente lo elige, la trazabilidad no
 * vale nada.
 */
private fun ApplicationCall.orderUser(): Long =
    userFromToken() ?: throw UnauthorizedException(
        code = "UNAUTHENTICATED",
        message = "No se pudo determinar quién ejecuta la operación.",
    )

private suspend fun ApplicationCall.requireOrderPermission(
    verifier: PermissionChecker,
    permission: String,
) {
    when (val decision = verifier.check(this, permission)) {
        is PermissionDecision.Permitted -> Unit
        is PermissionDecision.Denied ->
            if (decision.code == "UNAUTHENTICATED") {
                throw UnauthorizedException(decision.code, decision.message)
            } else {
                throw ForbiddenException(decision.code, decision.message)
            }
    }
}
