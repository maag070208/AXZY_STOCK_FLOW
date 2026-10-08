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
        val SORTABLE_FIELDS: Set<String> = setOf("hall", "code", "position", "createdAt")

        const val DEFAULT_ORDER = "hall"
    }

    /** Lista paginada, con los filtros que usa la pantalla de ubicaciones. */
    fun list(
        page: PageRequest,
        search: String?,
        warehouseId: Long?,
        kind: String?,
        active: Boolean?,
    ): Page<LocationRecord> = transaction(db) {
        val filter = filterOf(search, warehouseId, kind, active)
        val total = Locations.selectAll().withFilter(filter).count()

        val rows = queryBase()
            .withFilter(filter)
            .orderBy(sortColumn(page) to orderOf(page), Locations.id to orderOf(page))
            .limit(page.size)
            .offset(page.offset)
            .map(::aRecord)

        Page(items = rows, page = page.page, size = page.size, totalItems = total)
    }

    fun byId(id: Long): LocationRecord? = transaction(db) {
        queryBase().where { Locations.id eq id }.singleOrNull()?.let(::aRecord)
    }

    /** ¿Existe ya la terna en esa bodega? `excluyendoId` sirve para el `PUT`. */
    fun tripleExists(
        warehouseId: Long,
        hall: String,
        code: String,
        position: String,
        excludingId: Long?,
    ): Boolean = transaction(db) {
        Locations.selectAll()
            .where {
                var condition = (Locations.warehouseId eq warehouseId) and
                    (Locations.hall eq hall) and
                    (Locations.code eq code) and
                    (Locations.position eq position)
                // En un `PUT` la propia ubicación no cuenta como duplicado.
                excludingId?.let { condition = condition and (Locations.id neq it) }
                condition
            }
            .limit(1)
            .any()
    }

    fun create(data: NewLocation): LocationRecord = transaction(db) {
        val now = OffsetDateTime.now()

        val id = Locations.insert {
            it[warehouseId] = data.warehouseId
            it[hall] = data.hall
            it[code] = data.code
            it[position] = data.position
            it[levels] = data.levels
            // El cast explícito a `location_kind` se explica en CatalogTables.
            it[kind] = castToLocationKind(data.kind)
            it[active] = true
            it[createdAt] = now
            it[updatedAt] = now
        } get Locations.id

        byId(id) ?: error("La ubicación $id no se pudo releer después de crearla")
    }

    /** Devuelve `null` si la ubicación no existe. */
    fun update(id: Long, changes: ChangesLocation): LocationRecord? = transaction(db) {
        val rows = Locations.update({ Locations.id eq id }) {
            changes.hall?.let { value -> it[hall] = value }
            changes.code?.let { value -> it[code] = value }
            changes.position?.let { value -> it[position] = value }
            changes.levels?.let { value -> it[levels] = value }
            changes.kind?.let { value -> it[kind] = castToLocationKind(value) }
            // `active` se puede volver a poner en true por aquí: sin esto, una
            // ubicación desactivada por error solo se recuperaría por SQL.
            changes.active?.let { value -> it[active] = value }
            it[updatedAt] = OffsetDateTime.now()
        }

        if (rows == 0) null else byId(id)
    }

    /** Borrado lógico de la ubicación. `false` si ya estaba inactiva o no existe. */
    fun disable(id: Long): Boolean = transaction(db) {
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
    fun totalStockOf(locationId: Long): BigDecimal = transaction(db) {
        val sum = StockByLocation.quantity.sum()
        StockByLocation
            .select(sum)
            .where { StockByLocation.locationId eq locationId }
            .first()[sum] ?: BigDecimal.ZERO
    }

    // ── Interno ─────────────────────────────────────────────────────────────

    private fun queryBase() = Locations
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

    private fun aRecord(row: org.jetbrains.exposed.sql.ResultRow) = LocationRecord(
        id = row[Locations.id],
        warehouseId = row[Locations.warehouseId],
        warehouseCode = row[Warehouses.code],
        hall = row[Locations.hall],
        code = row[Locations.code],
        position = row[Locations.position],
        levels = row[Locations.levels],
        kind = row[Locations.kind],
        active = row[Locations.active],
    )

    private fun filterOf(
        search: String?,
        warehouseId: Long?,
        kind: String?,
        active: Boolean?,
    ): Op<Boolean>? {
        var filter: Op<Boolean>? = null
        fun accumulate(condition: Op<Boolean>) {
            filter = filter?.let { it and condition } ?: condition
        }

        warehouseId?.let { value -> accumulate(Op.build { Locations.warehouseId eq value }) }
        // El cast a `location_kind` es obligatorio también en el filtro: el
        // operador `=` de PostgreSQL está definido entre el ENUM y el ENUM.
        kind?.let { value ->
            accumulate(Op.build { Locations.kind eq castToLocationKind(value) })
        }
        active?.let { value -> accumulate(Op.build { Locations.active eq value }) }

        search?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val pattern = "%${text.lowercase()}%"
            accumulate(
                Op.build {
                    (Locations.hall.lowerCase() like pattern) or
                        (Locations.code.lowerCase() like pattern) or
                        (Locations.position.lowerCase() like pattern)
                }
            )
        }

        return filter
    }

    private fun sortColumn(page: PageRequest) = when (page.fieldOrder) {
        "code" -> Locations.code
        "position" -> Locations.position
        "createdAt" -> Locations.createdAt
        else -> Locations.hall
    }

    private fun orderOf(page: PageRequest): SortOrder =
        if (page.ascending) SortOrder.ASC else SortOrder.DESC
}

/** Los dos tipos de ubicación del esquema (`location_kind`). */
enum class LocationKind {
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
data class NewLocation(
    val warehouseId: Long,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int,
    val kind: String,
)

/** Campos que un `PUT` puede cambiar. `null` significa «no lo toques». */
data class ChangesLocation(
    val hall: String?,
    val code: String?,
    val position: String?,
    val levels: Int?,
    val kind: String?,
    val active: Boolean?,
)
