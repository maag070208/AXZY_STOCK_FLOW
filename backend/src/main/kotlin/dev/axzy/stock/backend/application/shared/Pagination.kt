package dev.axzy.stock.backend.application.shared

import dev.axzy.stock.backend.error.BadRequestException

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PAGINACIÓN Y ORDENACIÓN (§1.4 del contrato)
 *
 * El legacy devolvía **todos** los registros: con 1.398 productos y meses de
 * kardex, el listado de inventario cargaba la tabla entera y la paginación se
 * hacía en el navegador. Aquí el `LIMIT`/`OFFSET` viaja a PostgreSQL y la
 * respuesta lleva el total, para que el cliente sepa cuántas páginas hay sin
 * pedirlas todas.
 *
 * **Un `size` fuera de rango se rechaza con 400, no se recorta en silencio.**
 * Recortarlo hace que el cliente crea que tiene todos los datos, que es la
 * clase de error que se descubre tarde y en producción.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Cuántas filas y en qué orden pide el cliente. Ya validado. */
data class PageRequest(
    val page: Int,
    val size: Int,
    /** Campo de orden tal como lo nombró el cliente, ya verificado. */
    val fieldOrder: String,
    val ascending: Boolean,
) {
    /** Desplazamiento real que se pasa al `OFFSET` de la base. */
    val offset: Long get() = page.toLong() * size

    companion object {
        /** Tamaño por defecto del contrato. */
        const val DEFAULT_PAGE_SIZE = 50

        /** Tope del contrato. Un valor mayor se rechaza. */
        const val SIZE_MAXIMUM = 200

        /**
         * Valida y construye la paginación a partir de los parámetros crudos.
         *
         * Es una función pura —recibe cadenas, no un `ApplicationCall`— para
         * poder probar los bordes (negativos, tamaños excesivos, orden
         * desconocido) sin arrancar un servidor.
         *
         * @param camposOrdenables los campos admitidos por ESTE endpoint. El
         *   contrato exige documentarlos por endpoint, así que la lista no es
         *   global: `stock` no se puede ordenar aunque se devuelva.
         * @param ordenPorDefecto campo que se usa cuando no se pide `sort`.
         */
        fun analyze(
            page: String?,
            size: String?,
            sort: String?,
            fieldsSortable: Set<String>,
            defaultOrder: String,
        ): PageRequest {
            val rawPage = page?.trim()
            val numberPage = if (rawPage.isNullOrEmpty()) {
                0
            } else {
                rawPage.toIntOrNull() ?: throw BadRequestException(
                    code = "INVALID_PAGINATION",
                    message = "El parámetro 'page' debe ser un número entero. Se recibió '$rawPage'.",
                    details = mapOf("page" to rawPage),
                )
            }
            if (numberPage < 0) {
                throw BadRequestException(
                    code = "INVALID_PAGINATION",
                    message = "El parámetro 'page' empieza en 0 y no admite negativos. " +
                        "Se recibió $numberPage.",
                    details = mapOf("page" to numberPage.toString()),
                )
            }

            val rawSize = size?.trim()
            val numberSize = if (rawSize.isNullOrEmpty()) {
                DEFAULT_PAGE_SIZE
            } else {
                rawSize.toIntOrNull() ?: throw BadRequestException(
                    code = "INVALID_PAGINATION",
                    message = "El parámetro 'size' debe ser un número entero. Se recibió '$rawSize'.",
                    details = mapOf("size" to rawSize),
                )
            }
            // Se rechaza en lugar de recortar: 0 no tiene sentido (no hay página
            // posible) y por encima del tope el cliente se expone a descargar la
            // tabla entera, que es justo lo que se corrige.
            if (numberSize < 1 || numberSize > SIZE_MAXIMUM) {
                throw BadRequestException(
                    code = "INVALID_PAGINATION",
                    message = "El parámetro 'size' debe estar entre 1 y $SIZE_MAXIMUM. " +
                        "Se recibió $numberSize. No se recorta en silencio: un tamaño recortado " +
                        "haría creer al cliente que tiene todos los datos.",
                    details = mapOf("size" to numberSize.toString()),
                )
            }

            val (field, ascending) = analyzeSort(sort, fieldsSortable, defaultOrder)

            return PageRequest(
                page = numberPage,
                size = numberSize,
                fieldOrder = field,
                ascending = ascending,
            )
        }

        /**
         * `sort` es `campo,direccion`. La dirección es opcional y por defecto
         * ascendente, que es lo que espera un cliente que solo escribe
         * `sort=productCode`.
         */
        private fun analyzeSort(
            sort: String?,
            fieldsSortable: Set<String>,
            defaultOrder: String,
        ): Pair<String, Boolean> {
            val raw = sort?.trim()
            if (raw.isNullOrEmpty()) return defaultOrder to true

            val parts = raw.split(',')
            if (parts.size > 2) {
                throw BadRequestException(
                    code = "INVALID_SORT",
                    message = "El parámetro 'sort' es 'campo,direccion'. Se recibió '$raw'.",
                    details = mapOf("sort" to raw),
                )
            }

            val field = parts[0].trim()
            if (field !in fieldsSortable) {
                throw BadRequestException(
                    code = "INVALID_SORT",
                    message = "No se puede ordenar por '$field'. Campos admitidos en este " +
                        "endpoint: ${fieldsSortable.sorted().joinToString(", ")}.",
                    details = mapOf("sort" to field),
                )
            }

            val direction = parts.getOrNull(1)?.trim()?.lowercase()
            val ascending = when (direction) {
                null, "", "asc" -> true
                "desc" -> false
                else -> throw BadRequestException(
                    code = "INVALID_SORT",
                    message = "La dirección de orden debe ser 'asc' o 'desc'. Se recibió '$direction'.",
                    details = mapOf("sort" to raw),
                )
            }

            return field to ascending
        }
    }
}

/**
 * Una página ya resuelta.
 *
 * `totalItems` es el total de filas que cumplen el filtro, **no** el tamaño de
 * [items]: es lo que permite al cliente pintar «1.398 productos, 28 páginas»
 * con una sola petición.
 */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
) {
    /**
     * Número de páginas. Con cero resultados son cero páginas, no una: una
     * lista vacía que declara tener una página invita al cliente a pedir la
     * página 0 otra vez.
     */
    val totalPages: Int
        get() = if (totalItems <= 0L) 0 else ((totalItems + size - 1) / size).toInt()

    fun <R> map(transformation: (T) -> R): Page<R> =
        Page(items.map(transformation), page, size, totalItems)
}
