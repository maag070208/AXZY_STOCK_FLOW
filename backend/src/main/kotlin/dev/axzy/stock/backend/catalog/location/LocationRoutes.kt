package dev.axzy.stock.backend.catalog.location

import dev.axzy.stock.backend.catalog.LocationCreateRequest
import dev.axzy.stock.backend.catalog.LocationUpdateRequest
import dev.axzy.stock.backend.catalog.aDto
import dev.axzy.stock.backend.catalog.aResponse
import dev.axzy.stock.backend.catalog.filterBoolean
import dev.axzy.stock.backend.catalog.page
import dev.axzy.stock.backend.catalog.parameterLong
import dev.axzy.stock.backend.catalog.parseLocationKind
import dev.axzy.stock.backend.catalog.reasonOptional
import dev.axzy.stock.backend.catalog.requirePermission
import dev.axzy.stock.backend.catalog.routeId
import dev.axzy.stock.backend.security.PermissionChecker
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Rutas de ubicaciones. Traducen HTTP a [LocationService]; las reglas viven en el servicio.
 */
internal fun Route.locationRoutes(
    service: LocationService,
    verifier: PermissionChecker,
) = route("/v1/locations") {

    get {
        call.requirePermission(verifier, "location.view")

        val page = call.page(LocationRepository.SORTABLE_FIELDS, LocationRepository.DEFAULT_ORDER)
        val search = call.request.queryParameters["q"]
        val warehouseId = call.parameterLong("warehouseId")
        val kind = call.request.queryParameters["kind"]?.let(::parseLocationKind)?.name
        val active = call.filterBoolean("active")

        val result = service.list(page, search, warehouseId, kind, active)
        call.respond(HttpStatusCode.OK, result.aResponse { it.aDto() })
    }

    post {
        call.requirePermission(verifier, "location.create")

        val request = call.receive<LocationCreateRequest>()
        val created = service.create(
            warehouseId = request.warehouseId,
            hall = request.hall,
            code = request.code,
            position = request.position,
            levels = request.levels,
            kind = request.kind,
        )

        call.respond(HttpStatusCode.Created, created.aDto())
    }

    get("/{id}") {
        call.requirePermission(verifier, "location.view")

        val location = service.byId(call.routeId())
        call.respond(HttpStatusCode.OK, location.aDto())
    }

    put("/{id}") {
        call.requirePermission(verifier, "location.edit")

        val id = call.routeId()
        // Primero el 404: un id inexistente no debe responder 400 por un cuerpo inválido.
        service.byId(id)
        val request = call.receive<LocationUpdateRequest>()

        val updated = service.update(
            id = id,
            sentWarehouseId = request.warehouseId,
            hall = request.hall,
            code = request.code,
            position = request.position,
            levels = request.levels,
            kind = request.kind,
            active = request.active,
        )

        call.respond(HttpStatusCode.OK, updated.aDto())
    }

    delete("/{id}") {
        call.requirePermission(verifier, "location.delete")

        val id = call.routeId()
        // El motivo es opcional aquí (el contrato solo lo exige para productos).
        val reason = call.reasonOptional()

        service.disable(id, reason)
        call.respond(HttpStatusCode.NoContent)
    }
}
