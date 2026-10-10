package dev.axzy.stock.application.shared

import dev.axzy.stock.domain.common.asRight

/**
 * Paginación, orden y filtros: las reglas de docs/api/convenciones.md §1.4.
 *
 * **`size` mayor de 200 se RECHAZA, no se recorta.** Recortar en silencio hace que
 * el cliente crea que tiene todos los datos, que es peor que un error.
 *
 * Vive en `application/shared` porque la usan todos los listados de todos los
 * módulos: una segunda copia acabaría validando distinto.
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
 * El campo es el nombre **de la API** (`productCode`), no el de la columna: la
 * traducción a SQL vive en el adaptador, que es el único que conoce el esquema.
 */
data class SortSpec(val field: String, val direction: SortDirection = SortDirection.ASC) {
    override fun toString(): String = "$field,${direction.name.lowercase()}"
}

data class PageRequest(val page: Int, val size: Int, val sort: SortSpec) {
    val offset: Int get() = page * size

    companion object {
        const val DEFAULT_SIZE = 50

        /** El tope es una decisión, no un detalle: ver docs/api/convenciones.md §1.4. */
        const val MAX_SIZE = 200

        fun of(page: Int = 0, size: Int = DEFAULT_SIZE, sort: SortSpec = SortSpec("id")): PageRequest =
            PageRequest(page, size, sort)

        /**
         * Valida los parámetros de la query.
         *
         * @param allowedSorts nombres de campo que ese listado admite. Un campo
         *   fuera de la lista es `INVALID_SORT`, no una consulta a ciegas contra
         *   una columna que puede no existir.
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
                    "El número de página no puede ser negativo.",
                    mapOf("page" to "$page"),
                )
            }
            val effectiveSize = size ?: DEFAULT_SIZE
            if (effectiveSize !in 1..MAX_SIZE) {
                return failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PAGINATION",
                    "El tamaño de página debe estar entre 1 y $MAX_SIZE.",
                    mapOf("size" to "$effectiveSize"),
                )
            }
            val spec = parseSort(sort, allowedSorts, defaultSort)
                ?: return failure(
                    FailureKind.INVALID_REQUEST, "INVALID_SORT",
                    "Orden no admitido. Se admiten: ${allowedSorts.sorted().joinToString(", ")}.",
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
 * Una página de resultados.
 *
 * `totalItems` viene de un `count(*)` con **los mismos filtros** que el listado.
 * Nunca de `items.size`: el conteo que ignora los filtros es peor que no tenerlo
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
