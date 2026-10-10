package dev.axzy.stock.adapters.web.catalog

import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.catalog.CreateLocation
import dev.axzy.stock.application.catalog.DeactivateLocation
import dev.axzy.stock.application.catalog.GetLocation
import dev.axzy.stock.application.catalog.ListLocations
import dev.axzy.stock.application.catalog.LocationFilter
import dev.axzy.stock.application.catalog.LocationInput
import dev.axzy.stock.application.catalog.UpdateLocation
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.common.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Las rutas del catálogo de ubicaciones (docs/api/catalogo.md).
 *
 * `kind` se valida aquí y no se deja al `CAST` de PostgreSQL: un valor inválido
 * llegando a la base saldría como `500` por un dato del cliente, que es
 * exactamente lo que la lista cerrada de errores prohíbe.
 */
@RestController
@RequestMapping("/v1/locations")
class LocationController(
    private val listLocations: ListLocations,
    private val getLocation: GetLocation,
    private val createLocation: CreateLocation,
    private val updateLocation: UpdateLocation,
    private val deactivateLocation: DeactivateLocation,
) {

    @GetMapping
    @RequiresPermission("location.view")
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) warehouseId: Long?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) sort: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(page, size, sort, SORTS, SortSpec("hall"))
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        val kindFilter = when {
            kind.isNullOrBlank() -> null
            else -> LocationKind.parse(kind).getOrNull()
                ?: return failure(
                    FailureKind.RULE_VIOLATION, "INVALID_LOCATION_KIND",
                    "Invalid location kind '$kind': it must be SHELF or RECEIVING.",
                    mapOf("kind" to kind),
                ).toResponse()
        }
        return listLocations.execute(actor, LocationFilter(q, warehouseId, kindFilter, active), request, origin)
            .toResponse { it.toDto { view -> view.toDto() } }
    }

    @GetMapping("/{id}")
    @RequiresPermission("location.view")
    fun detail(@PathVariable id: Long, actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        getLocation.execute(actor, id, origin).toResponse { it.toDto() }

    @PostMapping
    @RequiresPermission("location.create")
    fun create(@RequestBody body: LocationRequest, actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        createLocation.execute(actor, body.toInput(), origin)
            .toResponse(HttpStatus.CREATED) { it.toDto() }

    @PutMapping("/{id}")
    @RequiresPermission("location.edit")
    fun update(
        @PathVariable id: Long,
        @RequestBody body: LocationRequest,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = updateLocation.execute(actor, id, body.toInput(), origin).toResponse { it.toDto() }

    @DeleteMapping("/{id}")
    @RequiresPermission("location.delete")
    fun deactivate(
        @PathVariable id: Long,
        @RequestBody(required = false) body: ReasonRequest?,
        @RequestParam(required = false) reason: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> =
        deactivateLocation.execute(actor, id, body?.reason ?: reason, origin).toResponse { it.toDto() }

    private companion object {
        val SORTS = setOf("hall", "code", "position", "createdAt")
    }
}

private fun LocationRequest.toInput() = LocationInput(warehouseId, hall, code, position, levels, kind, active)
