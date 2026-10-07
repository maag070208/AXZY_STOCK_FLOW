package dev.axzy.stock.backend.catalog

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.booleanLiteral
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REPOSITORIO DE UBICACIONES
 *
 * Una ubicación es la terna `hall` + `code` + `position` dentro de una bodega,
 * en mayúsculas. La ubicación de RECEPCIÓN (`kind = RECEIVING`) es la antigua
 * pseudo-ubicación `ADMIN` del legacy: allí vivía lo recibido y no ubicado,
 * pero era un objeto hardcodeado con `id` ficticio que podía colisionar con la
 * ubicación real 1. Aquí es una fila real, con `id` real.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LocationRepository(private val db: Database) {

    companion object {
        /**
         * Campos de `sort` admitidos.
         *
         * No se admite `displayName` porque **no es una columna**: se compone de
         * la terna. Ordenar por un campo derivado obligaría a ordenar en
         * memoria, que es justo lo que no se hace.
         */
        val CAMPOS_ORDENABLES: Set<String> = setOf("hall", "code", "position", "createdAt")

        const val ORDEN_POR_DEFECTO = "hall"
    }

    /** Lista paginada, con los filtros que usa la pantalla de ubicaciones. */
    fun listar(
        pagina: PageRequest,
        busqueda: String?,
        warehouseId: Long?,
        kind: String?,
        activo: Boolean?,
    ): Page<LocationRecord> = transaction(db) {
        val filtro = filtroDe(busqueda, warehouseId, kind, activo)
        val total = Locations.selectAll().conFiltro(filtro).count()

        val filas = consultaBase()
            .conFiltro(filtro)
            .orderBy(columnaDeOrden(pagina) to ordenDe(pagina), Locations.id to ordenDe(pagina))
            .limit(pagina.size)
            .offset(pagina.offset)
            .map(::aRegistro)

        Page(items = filas, page = pagina.page, size = pagina.size, totalItems = total)
    }

    fun porId(id: Long): LocationRecord? = transaction(db) {
        consultaBase().where { Locations.id eq id }.singleOrNull()?.let(::aRegistro)
    }

    /** ¿Existe ya la terna en esa bodega? `excluyendoId` sirve para el `PUT`. */
    fun existeTerna(
        warehouseId: Long,
        hall: String,
        code: String,
        position: String,
        excluyendoId: Long?,
    ): Boolean = transaction(db) {
        Locations.selectAll()
            .where {
                var condicion = (Locations.warehouseId eq warehouseId) and
                    (Locations.hall eq hall) and
                    (Locations.code eq code) and
                    (Locations.position eq position)
                // En un `PUT` la propia ubicación no cuenta como duplicado.
                excluyendoId?.let { condicion = condicion and (Locations.id neq it) }
                condicion
            }
            .limit(1)
            .any()
    }

    fun crear(datos: NuevaUbicacion): LocationRecord = transaction(db) {
        val ahora = OffsetDateTime.now()

        val id = Locations.insert {
            it[warehouseId] = datos.warehouseId
            it[hall] = datos.hall
            it[code] = datos.code
            it[position] = datos.position
            it[levels] = datos.levels
            // El cast explícito a `location_kind` se explica en CatalogTables.
            it[kind] = castAKindDeUbicacion(datos.kind)
            it[active] = true
            it[createdAt] = ahora
            it[updatedAt] = ahora
        } get Locations.id

        porId(id) ?: error("La ubicación $id no se pudo releer después de crearla")
    }

    /** Devuelve `null` si la ubicación no existe. */
    fun actualizar(id: Long, cambios: CambiosUbicacion): LocationRecord? = transaction(db) {
        val filas = Locations.update({ Locations.id eq id }) {
            cambios.hall?.let { valor -> it[hall] = valor }
            cambios.code?.let { valor -> it[code] = valor }
            cambios.position?.let { valor -> it[position] = valor }
            cambios.levels?.let { valor -> it[levels] = valor }
            cambios.kind?.let { valor -> it[kind] = castAKindDeUbicacion(valor) }
            // `active` se puede volver a poner en true por aquí: sin esto, una
            // ubicación desactivada por error solo se recuperaría por SQL.
            cambios.activo?.let { valor -> it[active] = valor }
            it[updatedAt] = OffsetDateTime.now()
        }

        if (filas == 0) null else porId(id)
    }

    /** Borrado lógico de la ubicación. `false` si ya estaba inactiva o no existe. */
    fun desactivar(id: Long): Boolean = transaction(db) {
        Locations.update({ (Locations.id eq id) and (Locations.active eq true) }) {
            it[active] = false
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    /**
     * Existencias guardadas en una ubicación.
     *
     * Se comprueba antes de desactivarla: una ubicación con stock no se puede
     * apagar, porque el inventario quedaría en un sitio invisible desde la
     * pantalla de ubicaciones y el descuadre aparecería en el conteo siguiente.
     */
    fun totalDeStockDe(locationId: Long): BigDecimal = transaction(db) {
        val suma = StockByLocation.quantity.sum()
        StockByLocation
            .select(suma)
            .where { StockByLocation.locationId eq locationId }
            .first()[suma] ?: BigDecimal.ZERO
    }

    // ── Interno ─────────────────────────────────────────────────────────────

    private fun consultaBase() = Locations
        .join(Warehouses, JoinType.INNER, Locations.warehouseId, Warehouses.id)
        .select(
            Locations.id,
            Locations.warehouseId,
            Locations.hall,
            Locations.code,
            Locations.position,
            Locations.levels,
            Locations.kind,
            Locations.active,
            Warehouses.code,
        )

    private fun aRegistro(fila: org.jetbrains.exposed.sql.ResultRow) = LocationRecord(
        id = fila[Locations.id],
        warehouseId = fila[Locations.warehouseId],
        warehouseCode = fila[Warehouses.code],
        hall = fila[Locations.hall],
        code = fila[Locations.code],
        position = fila[Locations.position],
        levels = fila[Locations.levels],
        kind = fila[Locations.kind],
        active = fila[Locations.active],
    )

    private fun filtroDe(
        busqueda: String?,
        warehouseId: Long?,
        kind: String?,
        activo: Boolean?,
    ): Op<Boolean>? {
        var filtro: Op<Boolean>? = null
        fun acumular(condicion: Op<Boolean>) {
            filtro = filtro?.let { it and condicion } ?: condicion
        }

        warehouseId?.let { valor -> acumular(Op.build { Locations.warehouseId eq valor }) }
        // El cast a `location_kind` es obligatorio también en el filtro: el
        // operador `=` de PostgreSQL está definido entre el ENUM y el ENUM.
        kind?.let { valor ->
            acumular(Op.build { Locations.kind eq castAKindDeUbicacion(valor) })
        }
        activo?.let { valor -> acumular(Op.build { Locations.active eq valor }) }

        busqueda?.trim()?.takeIf { it.isNotEmpty() }?.let { texto ->
            val patron = "%${texto.lowercase()}%"
            acumular(
                Op.build {
                    (Locations.hall.lowerCase() like patron) or
                        (Locations.code.lowerCase() like patron) or
                        (Locations.position.lowerCase() like patron)
                }
            )
        }

        return filtro
    }

    private fun columnaDeOrden(pagina: PageRequest) = when (pagina.campoOrden) {
        "code" -> Locations.code
        "position" -> Locations.position
        "createdAt" -> Locations.createdAt
        else -> Locations.hall
    }

    private fun ordenDe(pagina: PageRequest): SortOrder =
        if (pagina.ascendente) SortOrder.ASC else SortOrder.DESC
}

/** Los dos tipos de ubicación del esquema (`location_kind`). */
enum class TipoDeUbicacion {
    /** Estante normal. */
    SHELF,

    /** Zona de recepción: ahí vive lo recibido y no ubicado. */
    RECEIVING,
}

/** Una ubicación tal como la devuelve el repositorio. */
data class LocationRecord(
    val id: Long,
    val warehouseId: Long,
    val warehouseCode: String,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int,
    val kind: String,
    val active: Boolean,
) {
    /**
     * Nombre para mostrar: las tres partes y **sin repetir**.
     *
     * `04` + `B` + `C` → `04-B-C`. La ubicación de recepción tiene las tres
     * partes iguales (`RECEPCION`), así que se muestra como `RECEPCION` y no
     * como `RECEPCION-RECEPCION-RECEPCION`. El contrato ya usa `RECEPCION` como
     * nombre de destino en el kardex.
     */
    val displayName: String
        get() = listOf(hall, code, position).distinct().joinToString("-")
}

/** Datos de alta de una ubicación, ya normalizados por el servicio. */
data class NuevaUbicacion(
    val warehouseId: Long,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int,
    val kind: String,
)

/** Campos que un `PUT` puede cambiar. `null` significa «no lo toques». */
data class CambiosUbicacion(
    val hall: String?,
    val code: String?,
    val position: String?,
    val levels: Int?,
    val kind: String?,
    val activo: Boolean?,
)
