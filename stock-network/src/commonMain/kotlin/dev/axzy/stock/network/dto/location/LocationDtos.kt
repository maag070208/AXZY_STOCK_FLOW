package dev.axzy.stock.network.dto.location

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ALMACÉN FÍSICO — DTOs de `/v1/locations` y `/v1/warehouses`
 *
 * Contrato: docs/historico/CONTRATO_API.md §3.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Una ubicación física.
 *
 * `kind = RECEIVING` es la ubicación de recepción: **es real, con `id` real**,
 * no la pseudo-ubicación `ADMIN` con identificador ficticio que el legacy
 * devolvía hardcodeada y que podía colisionar con la ubicación 1.
 */
@Serializable
data class LocationDto(
    val id: Long,
    val warehouseId: Long,
    val warehouseCode: String? = null,
    val hall: String,
    val code: String,
    val position: String,
    /** Número de niveles de la estantería. */
    val levels: Int = 1,
    val kind: LocationKind = LocationKind.SHELF,
    val active: Boolean = true,
    /** Etiqueta legible, `"04-B-C"`. Es lo que se imprime y lo que ve el operador. */
    val displayName: String,
)

/**
 * Tipo de ubicación.
 *
 * Un enum y no una cadena porque el conjunto es cerrado y **el comportamiento
 * depende de él**: la ubicación de recepción tiene reglas propias (es el origen
 * de todo traslado de ubicación).
 */
@Serializable
enum class LocationKind {
    @SerialName("SHELF")
    SHELF,

    @SerialName("RECEIVING")
    RECEIVING,
}

/** Cuerpo de `POST /v1/locations`. */
@Serializable
data class CreateLocationRequest(
    val warehouseId: Long,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int = 1,
    val kind: LocationKind = LocationKind.SHELF,
    val active: Boolean = true,
)

/**
 * Cuerpo de `PUT /v1/locations/{id}`.
 *
 * AMBIGUO: el contrato no fija la forma del `PUT`. Se interpreta como «los
 * mismos campos del alta»; el `id` va en la ruta, no en el cuerpo.
 */
@Serializable
data class UpdateLocationRequest(
    val warehouseId: Long,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int = 1,
    val kind: LocationKind = LocationKind.SHELF,
    val active: Boolean = true,
)

/** Cuerpo del `DELETE /v1/locations/{id}`. AMBIGUO: el contrato no lo detalla. */
@Serializable
data class DeactivateLocationRequest(
    val reason: String,
)

/**
 * Una bodega.
 *
 * `isDefault` decide la bodega activa cuando el usuario no ha elegido ninguna.
 * Es un dato del servidor para que dos clientes no elijan bodegas distintas.
 */
@Serializable
data class WarehouseDto(
    val id: Long,
    val code: String,
    val name: String,
    val isDefault: Boolean = false,
    val active: Boolean = true,
)

/**
 * Respuesta de `GET /v1/warehouses`.
 *
 * **No es una [dev.axzy.stock.network.dto.common.Page]**: el contrato devuelve
 * `{ "items": [...] }` sin sobre de paginación. Se modela como es, en lugar de
 * forzarlo a un sobre que el servidor no manda. Las bodegas son pocas y no se
 * paginan.
 */
@Serializable
data class WarehousesDto(
    val items: List<WarehouseDto> = emptyList(),
)
