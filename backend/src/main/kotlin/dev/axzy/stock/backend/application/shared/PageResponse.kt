package dev.axzy.stock.backend.application.shared

import kotlinx.serialization.Serializable

/**
 * El sobre de paginación del contrato (§1.4): es el mismo en todos los listados.
 *
 * `totalItems` es el total **real** que cumple el filtro, nunca `items.size`: un total que
 * depende del `LIMIT` hace que el cliente crea que tiene todos los datos.
 */
@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

/** Convierte una página de la aplicación en el sobre del contrato. */
fun <T, R> Page<T>.toResponse(transformation: (T) -> R): PageResponse<R> = PageResponse(
    items = items.map(transformation),
    page = page,
    size = size,
    totalItems = totalItems,
    totalPages = totalPages,
)
