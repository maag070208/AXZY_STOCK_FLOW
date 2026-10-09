package dev.axzy.stock.backend.infrastructure.inbound.http.transfer

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
import dev.axzy.stock.backend.application.transfer.TransferFilter
import dev.axzy.stock.backend.application.transfer.TransferUseCases
import dev.axzy.stock.backend.infrastructure.outbound.persistence.transfer.JdbcTransferRepository
import javax.sql.DataSource

/**
 * Rutas de traslados (contrato §7).
 *
 * El listado de tránsito va **antes** de `/{id}`: Ktor resuelve por prioridad,
 * pero declararlo primero deja claro que `/in-transit` no es un identificador.
 */
fun Route.transferRoutes(
    dataSource: DataSource,
    permissionChecker: PermissionChecker,
) {
    val useCases = TransferUseCases(JdbcTransferRepository(dataSource))

    route("/v1/transfers") {

        get("/in-transit") {
            // `transit.view` y no `transfer.view`: el catálogo los separa **a
            // propósito** («Ver mercancía en tránsito» es su propia entrada), y
            // quien vigila la deuda de los traslados no tiene por qué poder ver ni
            // crear traslados. Usar el genérico dejaría el permiso específico sin
            // usar, que es la señal de que la ruta no lo respeta.
            call.requireTransferPermission(permissionChecker, "transit.view")
            call.respond(HttpStatusCode.OK, useCases.inTransit().map { it.toResponse() })
        }

        get {
            call.requireTransferPermission(permissionChecker, "transfer.view")

            val page = call.transferPage()
            val status = call.request.queryParameters["status"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (status != null && status !in TRANSFER_STATUSES) {
                throw BadRequestException(
                    code = "INVALID_PARAMETER",
                    message = "El parámetro 'status' no admite el valor '$status'.",
                    details = mapOf("status" to status, "allowed" to TRANSFER_STATUSES.joinToString(", ")),
                )
            }
            val warehouseId = call.request.queryParameters["warehouseId"]?.toLongOrNull()

            val items = useCases.list(TransferFilter(status, warehouseId), page.offset, page.size).map { it.toResponse() }
            val total = useCases.count(TransferFilter(status, warehouseId))

            call.respond(
                HttpStatusCode.OK,
                TransferPageResponse(
                    items = items,
                    page = page.page,
                    size = page.size,
                    totalItems = total,
                    totalPages = if (page.size == 0) 0 else ((total + page.size - 1) / page.size).toInt(),
                ),
            )
        }

        get("/{id}") {
            call.requireTransferPermission(permissionChecker, "transfer.view")
            val id = call.transferId()
            val detalle = useCases.byId(id)?.toResponse()
                ?: throw dev.axzy.stock.backend.error.NotFoundException(
                    "El traslado $id no existe.",
                    "TRANSFER_NOT_FOUND",
                )
            call.respond(HttpStatusCode.OK, detalle)
        }

        post {
            call.requireTransferPermission(permissionChecker, "transfer.create")
            val request = call.receive<TransferCreateRequest>()
            // `201` con el recurso: el cliente necesita el `id` para despacharlo.
            call.respond(HttpStatusCode.Created, useCases.create(request.toNew(), call.transferUser()).toResponse())
        }

        post("/{id}/dispatch") {
            call.requireTransferPermission(permissionChecker, "transfer.dispatch")
            val id = call.transferId()
            call.respond(HttpStatusCode.OK, useCases.dispatch(id, call.transferUser()).toResponse())
        }

        post("/{id}/receive") {
            call.requireTransferPermission(permissionChecker, "transfer.receive")
            val id = call.transferId()
            call.respond(HttpStatusCode.OK, useCases.receive(id, call.transferUser()).toResponse())
        }

        post("/{id}/cancel") {
            call.requireTransferPermission(permissionChecker, "transfer.cancel")
            val id = call.transferId()
            call.respond(HttpStatusCode.OK, useCases.cancel(id, call.transferUser()).toResponse())
        }
    }
}

/** El estado de un traslado, para validar el filtro contra el enum real. */
private val TRANSFER_STATUSES = listOf("DRAFT", "DISPATCHED", "IN_TRANSIT", "RECEIVED", "CANCELLED")

private data class TransferPage(val page: Int, val size: Int) {
    val offset: Int get() = page * size
}

private fun ApplicationCall.transferPage(): TransferPage {
    val brutoPagina = request.queryParameters["page"]
    val brutoTamano = request.queryParameters["size"]

    val pagina = brutoPagina?.trim()?.toIntOrNull()
        ?: if (brutoPagina.isNullOrBlank()) 0 else throw transferPaginationError("page", brutoPagina)
    val tamano = brutoTamano?.trim()?.toIntOrNull()
        ?: if (brutoTamano.isNullOrBlank()) 50 else throw transferPaginationError("size", brutoTamano)

    if (pagina < 0) throw transferPaginationError("page", brutoPagina)
    // Se RECHAZA en lugar de recortar: recortar hace creer al cliente que tiene
    // todos los datos (§1.4, y ya produjo dos fallos reales en este proyecto).
    if (tamano < 1 || tamano > 200) throw transferPaginationError("size", brutoTamano)

    return TransferPage(pagina, tamano)
}

private fun transferPaginationError(parametro: String, valor: String?) = BadRequestException(
    code = "INVALID_PAGINATION",
    message = "El parámetro '$parametro' no es válido. Se recibió '$valor'. " +
        "`page` empieza en 0 y `size` admite de 1 a 200.",
    details = mapOf(parametro to (valor ?: "")),
)

private fun ApplicationCall.transferId(): Long =
    parameters["id"]?.toLongOrNull()
        ?: throw BadRequestException("INVALID_ID", "El identificador del traslado no es un número.")

/**
 * El usuario que ejecuta la acción, **leído del token**.
 *
 * Se usa `userFromToken()`, el mismo helper que la recepción, y no un parámetro
 * de consulta: **un `?userId=` lo puede escribir cualquiera**, así que el
 * responsable de un traslado sería un dato que el cliente elige. Eso deja la
 * trazabilidad en papel mojado.
 *
 * Si no hay identificador se lanza `401`: el verificador ya comprobó el permiso,
 * así que llegar aquí sin sujeto significa que la identidad no se resolvió, y
 * seguir adelante anotaría un traslado sin responsable.
 */
private fun ApplicationCall.transferUser(): Long =
    userFromToken() ?: throw UnauthorizedException(
        code = "UNAUTHENTICATED",
        message = "No se pudo determinar quién ejecuta la operación.",
    )

private suspend fun ApplicationCall.requireTransferPermission(
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
