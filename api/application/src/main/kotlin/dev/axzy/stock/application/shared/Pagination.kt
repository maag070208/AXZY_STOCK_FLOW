package dev.axzy.stock.application.shared

import dev.axzy.stock.domain.common.asRight

/**
 * Pagination, sorting and filters: the rules of docs/api/convenciones.md §1.4.
 *
 * **`size` greater than 200 is REJECTED, not truncated.** Silently truncating makes
 * the client believe it has all the data, which is worse than an error.
 *
 * It lives in `application/shared` because all the listings of all the modules use it: a second copy
 * would end up validating differently.
 */

enum class SortDirection {
    ASC,
    DESC,
    ;

    companion object {
        fun parse(raw: String?): SortDirection? = when (raw?.trim()?.lowercase()) {
            null, "" -> ASC
            "asc" -> ASC
            "desc" -> DESC
            else -> null
        }
    }
}

/**
 * `campo,direccion`.
 *
 * The field is the **API's** name (`productCode`), not the column's: the translation to SQL lives in the
 * adapter, which is the only one that knows the schema.
 */
data class SortSpec(val field: String, val direction: SortDirection = SortDirection.ASC) {
    override fun toString(): String = "$field,${direction.name.lowercase()}"
}

data class PageRequest(val page: Int, val size: Int, val sort: SortSpec) {
    val offset: Int get() = page * size

    companion object {
        const val DEFAULT_SIZE = 50

        /** The cap is a decision, not a detail: see docs/api/convenciones.md §1.4. */
        const val MAX_SIZE = 200

        fun of(page: Int = 0, size: Int = DEFAULT_SIZE, sort: SortSpec = SortSpec("id")): PageRequest =
            PageRequest(page, size, sort)

        /**
         * Validates the query parameters.
         *
         * @param allowedSorts the field names that listing admits. A field
         *   outside the list is `INVALID_SORT`, not a blind query against
         *   a column that may not exist.
         */
        fun analyze(
            page: Int?,
            size: Int?,
            sort: String?,
            allowedSorts: Set<String>,
            defaultSort: SortSpec,
        ): Outcome<PageRequest> {
            if (page != null && page < 0) {
                return failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PAGINATION",
                    "The page number cannot be negative.",
                    mapOf("page" to "$page"),
                )
            }
            val effectiveSize = size ?: DEFAULT_SIZE
            if (effectiveSize !in 1..MAX_SIZE) {
                return failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PAGINATION",
                    "The page size must be between 1 and $MAX_SIZE.",
                    mapOf("size" to "$effectiveSize"),
                )
            }
            val spec = parseSort(sort, allowedSorts, defaultSort)
                ?: return failure(
                    FailureKind.INVALID_REQUEST, "INVALID_SORT",
                    "Sort not supported. Allowed: ${allowedSorts.sorted().joinToString(", ")}.",
                    mapOf("sort" to (sort ?: "")),
                )
            return PageRequest(page ?: 0, effectiveSize, spec).asRight()
        }

        private fun parseSort(sort: String?, allowed: Set<String>, default: SortSpec): SortSpec? {
            if (sort.isNullOrBlank()) return default
            val parts = sort.split(',')
            if (parts.size > 2) return null
            val field = parts[0].trim()
            if (field !in allowed) return null
            val direction = SortDirection.parse(parts.getOrNull(1)) ?: return null
            return SortSpec(field, direction)
        }
    }
}

/**
 * A page of results.
 *
 * `totalItems` comes from a `count(*)` with **the same filters** as the listing.
 * Never from `items.size`: a count that ignores the filters is worse than not having one
 * (docs/api/convenciones.md §1.4).
 */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
) {
    val totalPages: Int get() = if (totalItems <= 0L) 0 else ((totalItems + size - 1) / size).toInt()

    fun <B> map(transform: (T) -> B): Page<B> = Page(items.map(transform), page, size, totalItems)
}
