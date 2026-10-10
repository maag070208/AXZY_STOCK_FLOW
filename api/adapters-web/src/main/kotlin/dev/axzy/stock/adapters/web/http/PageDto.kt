package dev.axzy.stock.adapters.web.http

import dev.axzy.stock.application.shared.Page

/**
 * The envelope of a paginated list, defined **once** for the whole API:
 * (docs/api/convenciones.md §1.4).
 *
 * `totalItems` comes from the `count(*)` the use case ran with the same filters
 * as the page; never from `items.size`.
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
