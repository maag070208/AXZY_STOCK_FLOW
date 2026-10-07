package dev.axzy.stock.backend.catalog

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.booleanLiteral
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REPOSITORIO DE BODEGAS
 *
 * El esquema está preparado para N bodegas desde el inicio y se siembra una con
 * `is_default = true`: operar con una sola no cambia nada y añadir la segunda no
 * requiere migración.
 *
 * El contrato solo expone el listado (§3), así que aquí no hay altas: una
 * bodega nueva es una decisión de despliegue, no una operación de pantalla.
 *
 * **Este listado NO se pagina, y es la excepción deliberada de §1.4:** las
 * bodegas son un catálogo cerrado y diminuto (una o dos) que la interfaz
 * necesita COMPLETO para poblar un desplegable. Paginarlo obligaría al cliente a
 * pedir todas las páginas antes de dibujar un `select`. Lo que hace seguro
 * devolverlo entero es que crece solo cuando un administrador da de alta una
 * bodega, no con la operación como el kardex.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class WarehouseRepository(private val db: Database) {

    /**
     * Todas las bodegas, sin `LIMIT` y sin sobre de paginación.
     *
     * @param busqueda `q`, por si algún día hay muchas bodegas. Es una extensión
     *   sobre el contrato, no un requisito: sin filtros devuelve el catálogo
     *   completo, que es lo que espera el desplegable.
     * @param activo filtro opcional; por defecto se devuelven también las
     *   inactivas, porque una bodega desactivada no desaparece.
     */
    fun listarTodas(busqueda: String?, activo: Boolean?): List<WarehouseRecord> = transaction(db) {
        Warehouses
            .selectAll()
            .conFiltro(filtroDe(busqueda, activo))
            // El orden es fijo por código: sin paginación no hay `sort` que
            // interpretar, y un orden estable evita que el desplegable cambie
            // de orden entre recargas.
            .orderBy(Warehouses.code to SortOrder.ASC, Warehouses.id to SortOrder.ASC)
            .map { fila ->
                WarehouseRecord(
                    id = fila[Warehouses.id],
                    code = fila[Warehouses.code],
                    name = fila[Warehouses.name],
                    isDefault = fila[Warehouses.isDefault],
                    active = fila[Warehouses.active],
                )
            }
    }

    fun porId(id: Long): WarehouseRecord? = transaction(db) {
        Warehouses.selectAll().where { Warehouses.id eq id }.singleOrNull()?.let { fila ->
            WarehouseRecord(
                id = fila[Warehouses.id],
                code = fila[Warehouses.code],
                name = fila[Warehouses.name],
                isDefault = fila[Warehouses.isDefault],
                active = fila[Warehouses.active],
            )
        }
    }

    private fun filtroDe(busqueda: String?, activo: Boolean?): Op<Boolean>? {
        var filtro: Op<Boolean>? = null
        fun acumular(condicion: Op<Boolean>) {
            filtro = filtro?.let { it and condicion } ?: condicion
        }

        activo?.let { valor -> acumular(Op.build { Warehouses.active eq valor }) }

        busqueda?.trim()?.takeIf { it.isNotEmpty() }?.let { texto ->
            val patron = "%${texto.lowercase()}%"
            acumular(
                Op.build {
                    (Warehouses.code.lowerCase() like patron) or
                        (Warehouses.name.lowerCase() like patron)
                }
            )
        }

        return filtro
    }
}

/** Una bodega tal como la devuelve el repositorio. */
data class WarehouseRecord(
    val id: Long,
    val code: String,
    val name: String,
    val isDefault: Boolean,
    val active: Boolean,
)
