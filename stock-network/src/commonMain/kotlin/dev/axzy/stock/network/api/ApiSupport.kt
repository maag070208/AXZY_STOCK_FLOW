package dev.axzy.stock.network.api

import dev.axzy.stock.network.dto.common.ListQuery

/**
 * Convierte la paginación en parámetros de consulta.
 *
 * Los `null` **no se envían**: el cliente HTTP los descarta. Mandar
 * `?q=null` filtraría por el texto «null», que es el tipo de error que aparece
 * una vez en producción y cuesta una tarde encontrar.
 */
internal fun ListQuery.toParams(): List<Pair<String, String?>> = listOf(
    "page" to page.toString(),
    "size" to size.toString(),
    "sort" to sort,
    "q" to q,
)

/** Une los parámetros no nulos de un filtro con la paginación. */
internal fun List<Pair<String, String?>>.plusQuery(query: ListQuery): List<Pair<String, String?>> =
    this + query.toParams()
