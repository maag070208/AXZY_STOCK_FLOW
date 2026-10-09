package dev.axzy.stock.backend.infrastructure.inbound.http.transfer

import dev.axzy.stock.backend.application.transfer.TransferCreateRequest
import dev.axzy.stock.backend.application.transfer.TransferFilter
import dev.axzy.stock.backend.application.transfer.TransferPageResponse
import dev.axzy.stock.backend.application.transfer.TransferUseCases
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.pathLong
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.requirePermission
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.requireUser
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.simplePage
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.statusFilter
import dev.axzy.stock.backend.security.PermissionChecker
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Rutas de traslados (contrato §7).
 *
 * El listado de tránsito va **antes** de `/{id}`: Ktor resuelve por prioridad, pero declararlo
 * primero deja claro que `/in-transit` no es un identificador.
 */
fun Route.transferRoutes(
    useCases: TransferUseCases,
    permissionChecker: PermissionChecker,
) {
    route("/v1/transfers") {

        get("/in-transit") {
            // `transit.view` y no `transfer.view`: el catálogo los separa a propósito. Quien
            // vigila la deuda de los traslados no tiene por qué poder verlos ni crearlos.
            call.requirePermission(permissionChecker, "transit.view")
            call.respond(HttpStatusCode.OK, useCases.inTransit())
        }

        get {
            call.requirePermission(permissionChecker, "transfer.view")

            val page = call.simplePage()
            val filter = TransferFilter(
                status = call.statusFilter(TRANSFER_STATUSES),
                warehouseId = call.request.queryParameters["warehouseId"]?.toLongOrNull(),
            )

            val items = useCases.list(filter, page.offset, page.size)
            val total = useCases.count(filter)

            call.respond(
                HttpStatusCode.OK,
                TransferPageResponse(
                    items = items,
                    page = page.page,
                    size = page.size,
                    totalItems = total,
                    totalPages = page.totalPages(total),
                ),
            )
        }

        get("/{id}") {
            call.requirePermission(permissionChecker, "transfer.view")
            val id = call.transferId()
            val detail = useCases.byId(id)
                ?: throw NotFoundException("El traslado $id no existe.", "TRANSFER_NOT_FOUND")
            call.respond(HttpStatusCode.OK, detail)
        }

        post {
            call.requirePermission(permissionChecker, "transfer.create")
            val request = call.receive<TransferCreateRequest>()
            call.respond(HttpStatusCode.Created, useCases.create(request, call.transferUser()))
        }

        post("/{id}/dispatch") {
            call.requirePermission(permissionChecker, "transfer.dispatch")
            call.respond(HttpStatusCode.OK, useCases.dispatch(call.transferId(), call.transferUser()))
        }

        post("/{id}/receive") {
            call.requirePermission(permissionChecker, "transfer.receive")
            call.respond(HttpStatusCode.OK, useCases.receive(call.transferId(), call.transferUser()))
        }

        post("/{id}/cancel") {
            call.requirePermission(permissionChecker, "transfer.cancel")
            call.respond(HttpStatusCode.OK, useCases.cancel(call.transferId(), call.transferUser()))
        }
    }
}

/** Los estados de un traslado, para validar el filtro contra el enum real de la base. */
private val TRANSFER_STATUSES = listOf("DRAFT", "DISPATCHED", "IN_TRANSIT", "RECEIVED", "CANCELLED")

private fun ApplicationCall.transferId(): Long =
    pathLong("id", "El identificador del traslado no es un número.")

/**
 * El usuario que ejecuta la acción. Si no hay identificador se lanza `401`: el verificador ya
 * comprobó el permiso, así que llegar aquí sin sujeto significa que la identidad no se resolvió,
 * y seguir adelante anotaría un traslado sin responsable.
 */
private fun ApplicationCall.transferUser(): Long =
    requireUser("No se pudo determinar quién ejecuta la operación.")
