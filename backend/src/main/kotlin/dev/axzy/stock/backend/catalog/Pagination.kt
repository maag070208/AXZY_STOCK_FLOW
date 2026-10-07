package dev.axzy.stock.backend.catalog

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
    val campoOrden: String,
    val ascendente: Boolean,
) {
    /** Desplazamiento real que se pasa al `OFFSET` de la base. */
    val offset: Long get() = page.toLong() * size

    companion object {
        /** Tamaño por defecto del contrato. */
        const val TAMANO_POR_DEFECTO = 50

        /** Tope del contrato. Un valor mayor se rechaza. */
        const val TAMANO_MAXIMO = 200

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
        fun analizar(
            page: String?,
            size: String?,
            sort: String?,
            camposOrdenables: Set<String>,
            ordenPorDefecto: String,
        ): PageRequest {
            val crudoPage = page?.trim()
            val numeroPagina = if (crudoPage.isNullOrEmpty()) {
                0
            } else {
                crudoPage.toIntOrNull() ?: throw BadRequestException(
                    code = "INVALID_PAGINATION",
                    message = "El parámetro 'page' debe ser un número entero. Se recibió '$crudoPage'.",
                    details = mapOf("page" to crudoPage),
                )
            }
            if (numeroPagina < 0) {
                throw BadRequestException(
                    code = "INVALID_PAGINATION",
                    message = "El parámetro 'page' empieza en 0 y no admite negativos. " +
                        "Se recibió $numeroPagina.",
                    details = mapOf("page" to numeroPagina.toString()),
                )
            }

            val crudoSize = size?.trim()
            val numeroTamano = if (crudoSize.isNullOrEmpty()) {
                TAMANO_POR_DEFECTO
            } else {
                crudoSize.toIntOrNull() ?: throw BadRequestException(
                    code = "INVALID_PAGINATION",
                    message = "El parámetro 'size' debe ser un número entero. Se recibió '$crudoSize'.",
                    details = mapOf("size" to crudoSize),
                )
            }
            // Se rechaza en lugar de recortar: 0 no tiene sentido (no hay página
            // posible) y por encima del tope el cliente se expone a descargar la
            // tabla entera, que es justo lo que se corrige.
            if (numeroTamano < 1 || numeroTamano > TAMANO_MAXIMO) {
                throw BadRequestException(
                    code = "INVALID_PAGINATION",
                    message = "El parámetro 'size' debe estar entre 1 y $TAMANO_MAXIMO. " +
                        "Se recibió $numeroTamano. No se recorta en silencio: un tamaño recortado " +
                        "haría creer al cliente que tiene todos los datos.",
                    details = mapOf("size" to numeroTamano.toString()),
                )
            }

            val (campo, ascendente) = analizarOrden(sort, camposOrdenables, ordenPorDefecto)

            return PageRequest(
                page = numeroPagina,
                size = numeroTamano,
                campoOrden = campo,
                ascendente = ascendente,
            )
        }

        /**
         * `sort` es `campo,direccion`. La dirección es opcional y por defecto
         * ascendente, que es lo que espera un cliente que solo escribe
         * `sort=productCode`.
         */
        private fun analizarOrden(
            sort: String?,
            camposOrdenables: Set<String>,
            ordenPorDefecto: String,
        ): Pair<String, Boolean> {
            val crudo = sort?.trim()
            if (crudo.isNullOrEmpty()) return ordenPorDefecto to true

            val partes = crudo.split(',')
            if (partes.size > 2) {
                throw BadRequestException(
                    code = "INVALID_SORT",
                    message = "El parámetro 'sort' es 'campo,direccion'. Se recibió '$crudo'.",
                    details = mapOf("sort" to crudo),
                )
            }

            val campo = partes[0].trim()
            if (campo !in camposOrdenables) {
                throw BadRequestException(
                    code = "INVALID_SORT",
                    message = "No se puede ordenar por '$campo'. Campos admitidos en este " +
                        "endpoint: ${camposOrdenables.sorted().joinToString(", ")}.",
                    details = mapOf("sort" to campo),
                )
            }

            val direccion = partes.getOrNull(1)?.trim()?.lowercase()
            val ascendente = when (direccion) {
                null, "", "asc" -> true
                "desc" -> false
                else -> throw BadRequestException(
                    code = "INVALID_SORT",
                    message = "La dirección de orden debe ser 'asc' o 'desc'. Se recibió '$direccion'.",
                    details = mapOf("sort" to crudo),
                )
            }

            return campo to ascendente
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

    fun <R> map(transformacion: (T) -> R): Page<R> =
        Page(items.map(transformacion), page, size, totalItems)
}
