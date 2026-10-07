package dev.axzy.stock.backend.catalog

import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MAPEO DE LAS TABLAS DEL CATÁLOGO
 *
 * Estas tablas son un **mapeo**, no la fuente de verdad: el esquema lo poseen
 * las migraciones de Flyway (`V1__identity.sql`, `V3__inventory_domain.sql`).
 * Por eso **nunca se llama a `SchemaUtils.create`**: si Exposed creara el
 * esquema, habría dos definiciones del mismo esquema y la que gana es la que
 * se ejecute última. En el legacy el esquema se recreaba desde una migración
 * suelta, así que su historial no reconstruía la base.
 *
 * Solo se declaran las columnas que este módulo lee o escribe. Declarar las
 * demás no aporta nada y sí crea la tentación de escribir en tablas que son de
 * otro módulo (por ejemplo `kardex`, que es append-only y de inventario).
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Las bodegas.
 *
 * `is_default` tiene un índice único parcial en la base (`warehouses_one_default_idx`),
 * así que no puede haber dos bodegas por defecto: la regla la impone PostgreSQL
 * y no depende de que el código se acuerde.
 */
object Warehouses : Table("warehouses") {
    val id = long("id").autoIncrement()
    val code = varchar("code", 20)
    val name = varchar("name", 120)
    val isDefault = bool("is_default")
    val active = bool("active")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Las ubicaciones físicas.
 *
 * `hall`, `code` y `position` son la terna canónica y la base impone que estén
 * en mayúsculas (`locations_code_canonical`). Aquí se normalizan antes de
 * escribir para que el error sea un 422 explicable en lugar de una violación de
 * restricción con mensaje de PostgreSQL.
 */
object Locations : Table("locations") {
    val id = long("id").autoIncrement()
    val warehouseId = long("warehouse_id")
    val hall = varchar("hall", 20)
    val code = varchar("code", 20)
    val position = varchar("position", 20)
    val levels = integer("levels")

    /**
     * `location_kind` es un ENUM de PostgreSQL, no un VARCHAR.
     *
     * Se mapea como VARCHAR **solo para leer** (el driver devuelve la etiqueta
     * como texto y `active`/`kind` se consultan igual). Para escribir se usa
     * [CastAEnumPostgres]: el driver JDBC manda un String como `varchar` y
     * PostgreSQL no convierte `varchar` a un ENUM de forma implícita, así que
     * el `INSERT` fallaría con «column "kind" is of type location_kind but
     * expression is of type character varying». El cast explícito lo resuelve
     * sin depender de la opción `stringtype=unspecified` del driver, que hay
     * que recordar poner en la URL y se olvida.
     */
    val kind = varchar("kind", 20)
    val active = bool("active")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** Catálogo cerrado de unidades de medida. */
object UnitsOfMeasure : Table("units_of_measure") {
    val id = long("id").autoIncrement()
    val code = varchar("code", 8)
    val name = varchar("name", 40)
    val allowsFraction = bool("allows_fraction")

    override val primaryKey = PrimaryKey(id)
}

/**
 * El catálogo de productos.
 *
 * `product_code` es a la vez la clave de negocio y el código de barras: es lo
 * que está impreso en las etiquetas y en los estantes (ver el comentario de la
 * migración). Por eso `PUT` no lo modifica.
 */
object Products : Table("products") {
    val id = long("id").autoIncrement()
    val productCode = varchar("product_code", 20)
    val name = varchar("name", 120)
    val description = text("description").nullable()
    val unitMeasureId = long("unit_measure_id")
    val externalId = long("external_id").nullable()
    val active = bool("active")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Existencias por ubicación. **Es la fuente de verdad del inventario.**
 *
 * Este módulo solo la LEE: el stock lo mueven los casos de uso de inventario
 * (recepción, ubicación, traslado, ajuste). Escribirlo desde el CRUD de
 * catálogo saltaría el ledger y es exactamente el defecto del legacy.
 */
object StockByLocation : Table("stock_by_location") {
    val id = long("id").autoIncrement()
    val productId = long("product_id")
    val locationId = long("location_id")
    val warehouseId = long("warehouse_id")
    val quantity = decimal("quantity", 14, 3)
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Mercancía que salió de una bodega y todavía no entró en la otra.
 *
 * **Cuenta en el inventario total**, por eso entra en el cálculo de `stock` del
 * listado de productos: si se excluyera, el total bajaría durante el viaje y el
 * listado mentiría. Solo se suman los tránsitos no recibidos (`received_at IS NULL`).
 */
object StockInTransit : Table("stock_in_transit") {
    val id = long("id").autoIncrement()
    val productId = long("product_id")
    val fromWarehouseId = long("from_warehouse_id")
    val toWarehouseId = long("to_warehouse_id")
    val fromLocationId = long("from_location_id").nullable()
    val quantity = decimal("quantity", 14, 3)
    val dispatchedAt = timestampWithTimeZone("dispatched_at")
    val receivedAt = timestampWithTimeZone("received_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Escribe un valor en una columna de tipo ENUM de PostgreSQL con su cast
 * explícito: genera `?::location_kind`.
 *
 * Se implementa como expresión en lugar de declarar la columna como enum de
 * Exposed porque la columna del ENUM la posee la migración: el ENUM se crea con
 * `CREATE TYPE` y ningún `SchemaUtils` debe gestionarlo.
 */
class CastAEnumPostgres(
    private val valor: String,
    private val tipoPostgres: String,
) : Expression<String>() {

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            // El valor viaja como parámetro (nunca concatenado en el SQL: eso
            // sería una inyección) y el cast lo hace PostgreSQL.
            registerArgument(VarCharColumnType(), valor)
            append("::")
            append(tipoPostgres)
        }
    }
}

/** Cast de un valor concreto a la columna `locations.kind`. */
fun castAKindDeUbicacion(kind: String): Expression<String> =
    CastAEnumPostgres(kind, "location_kind")

/**
 * Aplica el `WHERE` solo si hay condiciones.
 *
 * Cuando no hay filtro **no se añade un `WHERE TRUE`**: la consulta sale sin
 * `WHERE`, que es lo que PostgreSQL ejecuta mejor y lo que se lee en el log.
 * Se evita además el falso `Op.TRUE`: en Exposed 0.59 no existe, y
 * `booleanLiteral(true)` es una expresión, no un `Op`, así que no sirve como
 * «sin filtro».
 */
fun Query.conFiltro(filtro: Op<Boolean>?): Query = if (filtro == null) this else where(filtro)
