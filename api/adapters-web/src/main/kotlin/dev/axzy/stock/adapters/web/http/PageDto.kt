package dev.axzy.stock.adapters.web.http

import dev.axzy.stock.application.shared.Page

/**
 * El sobre de un listado paginado, definido **una vez** para toda la API
 * (docs/api/convenciones.md §1.4).
 *
 * `totalItems` viene del `count(*)` que hizo el caso de uso con los mismos filtros
 * que la página; nunca de `items.size`.
 */
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

fun <T, R> Page<T>.toDto(transform: (T) -> R): PageDto<R> =
    PageDto(items.map(transform), page, size, totalItems, totalPages)
