package dev.axzy.stock.backend.catalog

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchUpsert
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
 * REPOSITORIO DE PRODUCTOS
 *
 * **La paginación se resuelve en la base de datos** (`LIMIT`/`OFFSET`), nunca
 * cargando la tabla y recortando en memoria. Con 1.398 productos da igual, y
 * con el catálogo de un cliente grande no: el listado del legacy traía todo y
 * por eso la pantalla de inventario tardaba.
 *
 * Las transacciones se abren aquí y son anidadas sin coste: si un caso de uso
 * ya abrió una transacción (el import, que debe ser atómico), estas operaciones
 * se **unen a ella** en lugar de confirmar por su cuenta. Es lo que hace que
 * «un import a medias» sea imposible.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ProductRepository(private val db: Database) {

    companion object {
        /** Campos de `sort` admitidos, tal como los documenta el contrato (§3). */
        val SORTABLE_FIELDS: Set<String> = setOf("productCode", "name", "createdAt")

        const val DEFAULT_ORDER = "productCode"
    }

    /**
     * Lista paginada.
     *
     * @param busqueda `q`, búsqueda libre sobre código y nombre.
     * @param activo filtro opcional. Por defecto **se devuelven también los
     *   inactivos**: un producto desactivado no desaparece del catálogo, se
     *   muestra como inactivo. Ocultarlo haría pensar que nunca existió y quien
     *   lo busca para reactivarlo no lo encontraría.
     */
    fun list(page: PageRequest, search: String?, active: Boolean?): Page<ProductRecord> =
        transaction(db) {
            val filter = filterOf(search, active)

            // El total se cuenta en la base, no contando la lista devuelta: es
            // lo que permite decir «1.398 productos, 28 páginas».
            val total = Products.selectAll().withFilter(filter).count()

            val rows = Products
                .join(UnitsOfMeasure, JoinType.INNER, Products.unitMeasureId, UnitsOfMeasure.id)
                .select(
                    Products.id,
                    Products.productCode,
                    Products.name,
                    Products.description,
                    Products.externalId,
                    Products.active,
                    UnitsOfMeasure.code,
                )
                .withFilter(filter)
                .orderBy(sortColumn(page) to orderOf(page), Products.id to orderOf(page))
                .limit(page.size)
                .offset(page.offset)
                .map { row ->
                    ProductRecord(
                        id = row[Products.id],
                        productCode = row[Products.productCode],
                        name = row[Products.name],
                        description = row[Products.description],
                        unitMeasure = row[UnitsOfMeasure.code],
                        externalId = row[Products.externalId],
                        active = row[Products.active],
                        stock = BigDecimal.ZERO,
                    )
                }

            // El stock de la página se resuelve con DOS consultas agregadas para
            // todos los ids, no con una por producto: sería el N+1 que el
            // listado no se puede permitir.
            val stocks = stockTotals(rows.map { it.id })
            Page(
                items = rows.map { it.copy(stock = stocks[it.id] ?: BigDecimal.ZERO) },
                page = page.page,
                size = page.size,
                totalItems = total,
            )
        }

    /** Un producto por su id, con su stock derivado. `null` si no existe. */
    fun byId(id: Long): ProductRecord? = transaction(db) {
        val row = Products
            .join(UnitsOfMeasure, JoinType.INNER, Products.unitMeasureId, UnitsOfMeasure.id)
            .select(
                Products.id,
                Products.productCode,
                Products.name,
                Products.description,
                Products.externalId,
                Products.active,
                UnitsOfMeasure.code,
            )
            .where { Products.id eq id }
            .singleOrNull()
            ?: return@transaction null

        ProductRecord(
            id = row[Products.id],
            productCode = row[Products.productCode],
            name = row[Products.name],
            description = row[Products.description],
            unitMeasure = row[UnitsOfMeasure.code],
            externalId = row[Products.externalId],
            active = row[Products.active],
            stock = totalStockOf(id),
        )
    }

    /** `true` si ya existe un producto con ese código. */
    fun existsCode(code: String): Boolean = transaction(db) {
        Products.selectAll().where { Products.productCode eq code }.limit(1).any()
    }

    /**
     * Crea un producto.
     *
     * No comprueba el duplicado: la base tiene un índice único sobre
     * `product_code` y es la comprobación que de verdad no tiene carrera. El
     * prechequeo del servicio existe solo para dar un 409 legible en el caso
     * normal; el que manda es el de la base.
     */
    fun create(data: NewProduct, unitOfMeasureId: Long): ProductRecord = transaction(db) {
        val now = OffsetDateTime.now()

        val id = Products.insert {
            it[productCode] = data.code
            it[name] = data.name
            it[description] = data.description
            it[unitMeasureId] = unitOfMeasureId
            // Un producto creado por la API no tiene id en el ERP. Se deja nulo
            // en lugar de inventarlo: la referencia cruzada o es real o no es.
            it[externalId] = null
            it[active] = true
            it[createdAt] = now
            it[updatedAt] = now
        } get Products.id

        ProductRecord(
            id = id,
            productCode = data.code,
            name = data.name,
            description = data.description,
            unitMeasure = data.unitMeasureCode,
            externalId = null,
            active = true,
            stock = BigDecimal.ZERO,
        )
    }

    /**
     * Actualiza los campos modificables. Devuelve `null` si el producto no existe.
     *
     * **`product_code` no se actualiza**: es el código de barras impreso en las
     * etiquetas y en los estantes; cambiarlo obliga a reetiquetar el almacén.
     * El catálogo de permisos tiene una acción aparte para eso
     * (`product.sync_barcode`), así que no puede colarse por un `PUT` normal.
     */
    fun update(id: Long, changes: ProductChanges, unitOfMeasureId: Long?): ProductRecord? =
        transaction(db) {
            val rows = Products.update({ Products.id eq id }) {
                changes.name?.let { value -> it[name] = value }
                // La descripción viaja siempre: en un `PUT` el cuerpo reemplaza
                // al recurso, así que omitirla significa «sin descripción» y es
                // la única forma de borrarla.
                it[description] = changes.description
                unitOfMeasureId?.let { value -> it[unitMeasureId] = value }
                it[updatedAt] = OffsetDateTime.now()
            }

            if (rows == 0) null else byId(id)
        }

    /**
     * Borrado lógico: `active = false`.
     *
     * Se hace con un `UPDATE` condicionado a `active = true` y se devuelve si
     * afectó a alguna fila. Un «leer, comprobar y escribir» tendría carrera:
     * dos peticiones simultáneas podrían desactivar dos veces y la segunda
     * pisaría el motivo de la primera.
     */
    fun disable(id: Long): Boolean = transaction(db) {
        Products.update({ (Products.id eq id) and (Products.active eq true) }) {
            it[active] = false
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    /**
     * Stock total derivado de un producto: `stock_by_location` **más** la
     * mercancía en tránsito no recibida.
     *
     * No se lee `product_stock` a propósito: es una proyección que mantiene la
     * capa de inventario, y el contrato define el total del catálogo como la
     * suma de las dos fuentes reales. Leerlas aquí hace que el listado sea
     * correcto aunque la proyección esté desfasada, en lugar de mentir con ella.
     */
    fun totalStockOf(productId: Long): BigDecimal = transaction(db) {
        val sumLocations = StockByLocation.quantity.sum()
        val atLocations = StockByLocation
            .select(sumLocations)
            .where { StockByLocation.productId eq productId }
            .first()[sumLocations] ?: BigDecimal.ZERO

        val sumTransit = StockInTransit.quantity.sum()
        val inTransit = StockInTransit
            .select(sumTransit)
            .where { (StockInTransit.productId eq productId) and (StockInTransit.receivedAt.isNull()) }
            .first()[sumTransit] ?: BigDecimal.ZERO

        atLocations + inTransit
    }

    /**
     * Inserta o actualiza el catálogo completo.
     *
     * La clave del upsert es `product_code`, que es la clave de identidad de
     * negocio (regla 1 del importador, §3.5). `active` y `created_at` **no se
     * tocan** al actualizar: reimportar el archivo no puede reactivar en
     * silencio un producto que alguien desactivó a propósito.
     *
     * Debe llamarse **dentro** de la transacción del import.
     *
     * Devuelve cuántas filas se escribieron. No se pide a Exposed que devuelva
     * los valores generados: serían 1.398 filas de `RETURNING` que nadie lee, y
     * la respuesta del import solo necesita el recuento.
     */
    fun bulkUpsert(rows: List<ImportRow>, unitCodes: Map<String, Long>): Int =
        transaction(db) {
            if (rows.isEmpty()) return@transaction 0

            val now = OffsetDateTime.now()
            Products.batchUpsert(
                data = rows,
                keys = arrayOf(Products.productCode),
                // `onUpdateExclude` deja escrito, en la propia llamada, qué NO se
                // actualiza al chocar con un código existente: `active` (un
                // producto desactivado a propósito no se reactiva al reimportar)
                // y `created_at` (la fecha de alta original se conserva).
                onUpdateExclude = listOf(Products.active, Products.createdAt),
                shouldReturnGeneratedValues = false,
            ) { row ->
                this[Products.productCode] = row.code
                this[Products.name] = row.name
                this[Products.description] = null
                this[Products.unitMeasureId] = unitCodes.getValue(row.unitOfMeasure)
                this[Products.externalId] = row.idExternal
                this[Products.active] = true
                this[Products.createdAt] = now
                this[Products.updatedAt] = now
            }

            rows.size
        }

    // ── Interno ─────────────────────────────────────────────────────────────

    /**
     * Filtro compartido por el listado. Se compone con `AND` y se resuelve en
     * la base: `q` no se aplica en memoria.
     */
    private fun filterOf(search: String?, active: Boolean?): Op<Boolean>? {
        // `Op.build` da el receptor `SqlExpressionBuilder`, que es donde viven
        // `eq`, `like` y `lowerCase`. Sin él habría que importarlos uno a uno.
        var filter: Op<Boolean>? = null
        fun accumulate(condition: Op<Boolean>) {
            filter = filter?.let { it and condition } ?: condition
        }

        active?.let { value -> accumulate(Op.build { Products.active eq value }) }

        search?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            // El patrón LIKE viaja como parámetro (nunca concatenado en el SQL).
            // Un `%` escrito por el usuario actúa como comodín: es una búsqueda
            // libre, no una comparación exacta.
            val pattern = "%${text.lowercase()}%"
            accumulate(
                Op.build {
                    (Products.productCode.lowerCase() like pattern) or
                        (Products.name.lowerCase() like pattern)
                }
            )
        }

        return filter
    }

    /** Traduce el campo de orden validado a su columna. */
    private fun sortColumn(page: PageRequest) = when (page.fieldOrder) {
        "name" -> Products.name
        "createdAt" -> Products.createdAt
        else -> Products.productCode
    }

    private fun orderOf(page: PageRequest): SortOrder =
        if (page.ascending) SortOrder.ASC else SortOrder.DESC

    /**
     * Stock derivado de varios productos, en dos consultas agregadas.
     *
     * Devuelve solo los productos que aparecen en alguna de las dos tablas; el
     * que no aparece tiene 0 y el llamador lo resuelve con un valor por defecto.
     */
    private fun stockTotals(ids: Collection<Long>): Map<Long, BigDecimal> {
        if (ids.isEmpty()) return emptyMap()

        val accumulated = mutableMapOf<Long, BigDecimal>()

        val sumLocations = StockByLocation.quantity.sum()
        StockByLocation
            .select(StockByLocation.productId, sumLocations)
            .where { StockByLocation.productId inList ids }
            .groupBy(StockByLocation.productId)
            .forEach { row ->
                accumulated[row[StockByLocation.productId]] = row[sumLocations] ?: BigDecimal.ZERO
            }

        val sumTransit = StockInTransit.quantity.sum()
        StockInTransit
            .select(StockInTransit.productId, sumTransit)
            .where {
                (StockInTransit.productId inList ids) and (StockInTransit.receivedAt.isNull())
            }
            .groupBy(StockInTransit.productId)
            .forEach { row ->
                val product = row[StockInTransit.productId]
                accumulated[product] = (accumulated[product] ?: BigDecimal.ZERO) +
                    (row[sumTransit] ?: BigDecimal.ZERO)
            }

        return accumulated
    }
}

/** Un producto tal como lo devuelve el repositorio, con su stock ya resuelto. */
data class ProductRecord(
    val id: Long,
    val productCode: String,
    val name: String,
    val description: String?,
    val unitMeasure: String,
    val externalId: Long?,
    val active: Boolean,
    val stock: BigDecimal,
)

/** Datos de alta de un producto. Sin `id`: lo genera la base. */
data class NewProduct(
    val code: String,
    val name: String,
    val description: String?,
    val unitMeasureCode: String,
)

/**
 * Campos que un `PUT` puede cambiar.
 *
 * `nombre` es nulo solo cuando el servicio no lo validó; en la práctica el
 * `PUT` es un reemplazo y siempre trae nombre y unidad.
 */
data class ProductChanges(
    val name: String?,
    val description: String?,
    val unitOfMeasure: String?,
)

/**
 * Una fila validada por el dominio, lista para el upsert masivo.
 *
 * Es una traducción de `ValidatedProduct` a tipos de la capa de persistencia:
 * la capa HTTP y la de base no comparten los objetos del dominio.
 */
data class ImportRow(
    val code: String,
    val name: String,
    val idExternal: Long?,
    val unitOfMeasure: String,
)
