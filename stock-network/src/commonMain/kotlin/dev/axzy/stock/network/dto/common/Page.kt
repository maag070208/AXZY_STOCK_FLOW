package dev.axzy.stock.network.dto.common

import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL SOBRE DE PAGINACIÓN — `Page<T>`
 *
 * **Todas las listas de la API v1 se paginan**, y todas usan esta misma forma.
 * Está aquí, en un solo sitio, precisamente para que no haya dos: el legacy
 * devolvía listas completas en unos endpoints y `{data, total}` en otros, y el
 * cliente acabó adivinando la forma de cada respuesta.
 *
 * El defecto que corrige: el legacy devolvía **todos** los registros. Con 1.398
 * productos y meses de kardex, el listado de inventario cargaba la tabla entera
 * en el móvil y en la web.
 *
 * Contrato: docs/historico/CONTRATO_API.md §1.4.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    /** Número de página. **Empieza en 0**, como Spring y la mayoría de clientes. */
    val page: Int = FIRST_PAGE,
    val size: Int = DEFAULT_SIZE,
    val totalItems: Int = 0,
    val totalPages: Int = 0,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** Indica si hay una página siguiente. La UI no tiene que saber aritmética de páginas. */
    val hasNext: Boolean get() = page + 1 < totalPages

    /** Mapea los elementos conservando el sobre: el mapper no tiene que rehacerlo. */
    fun <R> map(transform: (T) -> R): Page<R> = Page(
        items = items.map(transform),
        page = page,
        size = size,
        totalItems = totalItems,
        totalPages = totalPages,
    )

    companion object {
        /** El contrato empieza en 0. Usar 1 como primera página desplaza todas las listas. */
        const val FIRST_PAGE = 0

        /** Tamaño por defecto, si el cliente no lo pide. */
        const val DEFAULT_SIZE = 50

        /**
         * Tamaño máximo. Un `size` mayor **se rechaza con 400**, no se recorta en
         * silencio: recortar haría creer al cliente que tiene todos los datos.
         * Está aquí para que el cliente no tenga que descubrirlo con un 400.
         */
        const val MAX_SIZE = 200
    }
}

/**
 * Los parámetros de una lista: `?page=0&size=50&sort=productCode,asc&q=abra`.
 *
 * Se valida en el constructor porque un `size` fuera de rango es un error del
 * programador, no del usuario: es mejor que falle aquí, en el sitio donde se
 * construyó, que con un `400` en el servidor.
 */
data class ListQuery(
    val page: Int = Page.FIRST_PAGE,
    val size: Int = Page.DEFAULT_SIZE,
    /** `campo,direccion`. Los campos admitidos se documentan por endpoint. */
    val sort: String? = null,
    /** Búsqueda libre. */
    val q: String? = null,
) {
    init {
        require(page >= 0) { "La página empieza en 0, se pidió $page" }
        require(size in 1..Page.MAX_SIZE) {
            "El tamaño de página debe estar entre 1 y ${Page.MAX_SIZE}, se pidió $size"
        }
    }

    companion object {
        /**
         * Atajo para ordenar por un campo con su dirección.
         * `sort("productCode", ASC)` → `"productCode,asc"`.
         */
        fun sortOf(field: String, direction: SortDirection = SortDirection.ASC): String =
            "$field,${direction.value}"
    }
}

/** Dirección de ordenación. El contrato la expresa como texto en `sort`. */
enum class SortDirection(val value: String) {
    ASC("asc"),
    DESC("desc"),
}
