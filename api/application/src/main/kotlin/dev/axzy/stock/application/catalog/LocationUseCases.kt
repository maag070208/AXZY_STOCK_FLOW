package dev.axzy.stock.application.catalog

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.application.shared.toOutcome
import dev.axzy.stock.domain.access.ResourceContext
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.catalog.LocationPart
import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.common.flatMap
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.application.shared.toFailure

/** La ubicación tal como llega del cliente. `warehouseId` solo se admite al crear. */
data class LocationInput(
    val warehouseId: Long? = null,
    val hall: String? = null,
    val code: String? = null,
    val position: String? = null,
    val levels: Int? = null,
    val kind: String? = null,
    val active: Boolean? = null,
)

class ListLocations(private val locations: Locations, private val access: AccessDecider) {

    /**
     * Cuando el cliente filtra por bodega, la bodega viaja al motor de acceso: un
     * jefe de bodega con alcance `AREA` no puede listar la bodega de otro, y el
     * `403 SCOPE_INSUFFICIENT` lo dice. Sin filtro, el listado cubre las bodegas
     * del alcance.
     */
    fun execute(actor: Actor, filter: LocationFilter, request: PageRequest, origin: RequestOrigin): Outcome<Page<LocationView>> =
        access.authorizeOr(
            actor, "location.view", origin,
            resource = ResourceContext(warehouseId = filter.warehouseId),
        ) { locations.page(filter, request).asRight() }
}

class GetLocation(private val locations: Locations, private val access: AccessDecider) {

    fun execute(actor: Actor, id: Long, origin: RequestOrigin): Outcome<LocationView> =
        access.authorizeOr(actor, "location.view", origin, entityType = "location", entityId = "$id") {
            locations.findById(id)?.takeIf { it.location.active }
                ?.asRight()
                ?: DomainError.LocationNotFound("$id").toFailure().asLeft()
        }
}

class CreateLocation(
    private val locations: Locations,
    private val warehouses: Warehouses,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {
    fun execute(actor: Actor, body: LocationInput, origin: RequestOrigin): Outcome<LocationView> {
        val warehouseId = body.warehouseId
            ?: return failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "La bodega es obligatoria.")

        return access.authorizeOr(
            actor, "location.create", origin,
            resource = ResourceContext(warehouseId = warehouseId),
        ) { grant ->
            if (warehouses.findById(warehouseId) == null) {
                return@authorizeOr DomainError.UnknownWarehouse(warehouseId).toFailure().asLeft()
            }
            prepared(body).toOutcome().flatMap { input: PreparedLocation ->
                if (locations.find(warehouseId, input.hall.value, input.code.value, input.position.value) != null) {
                    DomainError.DuplicateLocation(input.hall.value, input.code.value, input.position.value)
                        .toFailure().asLeft()
                } else {
                    tx.inTransaction {
                        val created = locations.insert(
                            NewLocation(
                                warehouseId = warehouseId,
                                hall = input.hall.value,
                                code = input.code.value,
                                position = input.position.value,
                                levels = input.levels,
                                kind = input.kind,
                            ),
                        )
                        audit.record(
                            auditEvents.byActor(
                                actor = actor,
                                origin = origin,
                                action = "LOCATION_CREATED",
                                level = AuditLevel.RELEVANT,
                                entity = AuditEntity(
                                    "location", created.location.id.toString(), created.location.displayName,
                                ),
                                grant = grant,
                                newState = created.location.snapshot(),
                            ),
                        )
                        created.asRight()
                    }
                }
            }
        }
    }
}

class UpdateLocation(
    private val locations: Locations,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {
    fun execute(actor: Actor, id: Long, body: LocationInput, origin: RequestOrigin): Outcome<LocationView> {
        val current = locations.findById(id)
            ?: return DomainError.LocationNotFound("$id").toFailure().asLeft()
        val location = current.location

        return access.authorizeOr(
            actor, "location.edit", origin,
            resource = ResourceContext(warehouseId = location.warehouseId, locationKind = location.kind.name),
            entityType = "location",
            entityId = "$id",
        ) { grant ->
            // Mover una ubicación de bodega cambiaría de bodega su stock sin ningún
            // movimiento de inventario: se rechaza aunque el cuerpo lo traiga igual.
            if (body.warehouseId != null && body.warehouseId != location.warehouseId) {
                return@authorizeOr DomainError.WarehouseImmutable(location.id).toFailure().asLeft()
            }
            prepared(body, location).toOutcome().flatMap { input: PreparedLocation ->
                val clash = locations.find(
                    location.warehouseId,
                    input.hall.value,
                    input.code.value,
                    input.position.value,
                )
                if (clash != null && clash.location.id != location.id) {
                    DomainError.DuplicateLocation(input.hall.value, input.code.value, input.position.value)
                        .toFailure().asLeft()
                } else {
                    location.withUpdated(
                        hall = input.hall,
                        code = input.code,
                        position = input.position,
                        levels = input.levels,
                        active = input.active,
                    ).toOutcome().flatMap { updated: Location ->
                        tx.inTransaction {
                            val saved = locations.update(updated)
                            audit.record(
                                auditEvents.byActor(
                                    actor = actor,
                                    origin = origin,
                                    action = "LOCATION_UPDATED",
                                    level = AuditLevel.RELEVANT,
                                    entity = AuditEntity(
                                        "location", saved.location.id.toString(), saved.location.displayName,
                                    ),
                                    grant = grant,
                                    previousState = location.snapshot(),
                                    newState = saved.location.snapshot(),
                                ),
                            )
                            saved.asRight()
                        }
                    }
                }
            }
        }
    }
}

class DeactivateLocation(
    private val locations: Locations,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {
    fun execute(actor: Actor, id: Long, reason: String?, origin: RequestOrigin): Outcome<LocationView> {
        val current = locations.findById(id)
            ?: return DomainError.LocationNotFound("$id").toFailure().asLeft()
        val location = current.location

        return access.authorizeOr(
            actor, "location.delete", origin,
            resource = ResourceContext(warehouseId = location.warehouseId, locationKind = location.kind.name),
            entityType = "location",
            entityId = "$id",
        ) { grant ->
            location.deactivate().toOutcome().flatMap { deactivated: Location ->
                val stock = locations.stockOf(deactivated.id)
                val deactivatedOutcome: Outcome<LocationView> = if (stock.isPositive) {
                    DomainError.LocationHasStock(stock.toString()).toFailure().asLeft()
                } else {
                    tx.inTransaction {
                        val saved = locations.update(deactivated)
                        audit.record(
                            auditEvents.byActor(
                                actor = actor,
                                origin = origin,
                                action = "LOCATION_DEACTIVATED",
                                level = AuditLevel.RELEVANT,
                                entity = AuditEntity(
                                    "location", saved.location.id.toString(), saved.location.displayName,
                                ),
                                grant = grant,
                                previousState = location.snapshot(),
                                newState = saved.location.snapshot(),
                                reason = reason?.trim()?.ifBlank { null },
                            ),
                        )
                        saved.asRight()
                    }
                }
                deactivatedOutcome
            }
        }
    }
}

// ── Validación compartida ───────────────────────────────────────────────────

private data class PreparedLocation(
    val hall: LocationPart,
    val code: LocationPart,
    val position: LocationPart,
    val levels: Int,
    val kind: LocationKind,
    val active: Boolean,
)

private fun prepared(body: LocationInput, current: Location? = null): DomainResult<PreparedLocation> =
    LocationPart.of(body.hall.orEmpty(), "hall").flatMap { hall: LocationPart ->
        LocationPart.of(body.code.orEmpty(), "code").flatMap { code: LocationPart ->
            LocationPart.of(body.position.orEmpty(), "position").flatMap { position: LocationPart ->
                resolveKind(body.kind, current).flatMap { kind: LocationKind ->
                    val levels = body.levels ?: current?.levels ?: 1
                    if (levels < 1) {
                        DomainError.InvalidLevels(levels).asLeft()
                    } else {
                        PreparedLocation(
                            hall = hall,
                            code = code,
                            position = position,
                            levels = levels,
                            kind = kind,
                            active = body.active ?: current?.active ?: true,
                        ).asRight()
                    }
                }
            }
        }
    }

private fun resolveKind(raw: String?, current: Location?): DomainResult<LocationKind> = when {
    raw.isNullOrBlank() -> (current?.kind ?: LocationKind.SHELF).asRight()
    else -> LocationKind.parse(raw).fold(
        onSuccess = { it.asRight() },
        onFailure = { DomainError.InvalidLocationKind(raw).asLeft() },
    )
}

private fun Location.snapshot(): Map<String, Any?> = mapOf(
    "warehouseId" to warehouseId,
    "hall" to hall,
    "code" to code,
    "position" to position,
    "levels" to levels,
    "kind" to kind.name,
    "displayName" to displayName,
    "active" to active,
)
