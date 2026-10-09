package dev.axzy.stock.backend.catalog.warehouse

import dev.axzy.stock.backend.catalog.WarehouseListResponse
import dev.axzy.stock.backend.catalog.aDto
import dev.axzy.stock.backend.catalog.filterBoolean
import dev.axzy.stock.backend.catalog.location.aDto
import dev.axzy.stock.backend.catalog.product.aDto
import dev.axzy.stock.backend.catalog.requirePermission
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.domain.value.ProductCode
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import javax.sql.DataSource

/** Rutas de bodegas. */
internal fun Route.warehouseRoutes(
    warehouses: WarehouseRepository,
    verifier: PermissionChecker,
) = route("/v1/warehouses") {

    get {
        // §11 no lista `GET /v1/warehouses`; §1.4 dice que este catálogo exige
        // `location.view`, que es el permiso que el dominio define para ver el
        // almacén físico. Dejarla sin permiso sería repetir el defecto del
        // legacy: rutas que parecían protegidas y no lo estaban.
        call.requirePermission(verifier, "location.view")

        // Sin paginación, y es deliberado: §1.4 exceptúa las bodegas porque son
        // un catálogo cerrado y diminuto que la interfaz necesita COMPLETO para
        // poblar el desplegable. Devolver solo la primera página obligaría al
        // cliente a pedirlas todas antes de poder dibujar el `select`.
        val search = call.request.queryParameters["q"]
        val active = call.filterBoolean("active")

        val warehousesFound = warehouses.listAll(search, active)
        call.respond(HttpStatusCode.OK, WarehouseListResponse(warehousesFound.map { it.aDto() }))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// AYUDANTES
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Exige el permiso de §11 y traduce la denegación al código correcto.
 *
 * `UNAUTHENTICATED` es 401 (falta identidad, hay que iniciar sesión) y todo lo
 * demás es 403 con su código propio: `PERMISSION_DENIED`, `SCOPE_INSUFFICIENT`,
 * `POLICY_DENIED` o `SEGREGATION_OF_DUTIES`. Colapsarlos en un 403 genérico es
 * el defecto del legacy: el usuario no sabe si pedir un permiso, esperar o
 * llamar a otra persona.
 */
