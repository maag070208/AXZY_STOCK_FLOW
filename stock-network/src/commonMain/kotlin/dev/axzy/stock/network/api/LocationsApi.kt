package dev.axzy.stock.network.api

import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.domain.common.map
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.common.Page
import dev.axzy.stock.network.dto.location.CreateLocationRequest
import dev.axzy.stock.network.dto.location.DeactivateLocationRequest
import dev.axzy.stock.network.dto.location.LocationDto
import dev.axzy.stock.network.dto.location.UpdateLocationRequest
import dev.axzy.stock.network.dto.location.WarehouseDto
import dev.axzy.stock.network.dto.location.WarehousesDto
import dev.axzy.stock.network.error.ApiOutcome
import kotlinx.serialization.encodeToString

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/locations` y `/v1/warehouses` — almacén físico
 *
 * Contrato: CONTRATO_API.md §3.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LocationsApi internal constructor(private val http: StockHttpClient) {

    /**
     * Lista paginada de ubicaciones.
     *
     * El contrato solo muestra el objeto suelto, pero §1.4 dice que **toda lista
     * se pagina**: se devuelve el sobre, no una lista pelada. Es la
     * interpretación coherente con el resto del contrato.
     */
    suspend fun list(query: ListQuery = ListQuery()): ApiOutcome<Page<LocationDto>> =
        http.getText("/locations", query.toParams()).decode()

    suspend fun get(id: Long): ApiOutcome<LocationDto> = http.getText("/locations/$id").decode()

    suspend fun create(request: CreateLocationRequest): ApiOutcome<LocationDto> =
        http.postText("/locations", stockJson.encodeToString(request)).decode()

    suspend fun update(id: Long, request: UpdateLocationRequest): ApiOutcome<LocationDto> =
        http.putText("/locations/$id", stockJson.encodeToString(request)).decode()

    suspend fun deactivate(id: Long, request: DeactivateLocationRequest): ApiOutcome<Unit> =
        http.deleteNoContent("/locations/$id", stockJson.encodeToString(request))

    /**
     * Bodegas.
     *
     * **No se pagina**: el contrato devuelve `{ "items": [...] }` sin sobre. Se
     * modela como es, en lugar de forzarlo a una página que el servidor no manda.
     */
    suspend fun warehouses(): ApiOutcome<List<WarehouseDto>> =
        http.getText("/warehouses").decode<WarehousesDto>().map { it.items }
}
