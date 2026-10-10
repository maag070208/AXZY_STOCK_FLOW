package dev.axzy.stock.domain.catalog

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.ProductCode

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CATÁLOGO — productos, bodegas y ubicaciones (M01)
 *
 * Las reglas que gobiernan la fuente maestra de todo lo demás. Se prueban sin
 * base de datos: son las que deciden si una operación es admisible, y la base
 * solo las refuerza (índices únicos y `CHECK`), nunca las sustituye.
 *
 * Tres reglas que no son de forma sino de negocio, y por eso están aquí:
 *
 *  1. **El código de producto no se cambia.** Está impreso en las etiquetas y en
 *     los estantes: cambiarlo deja el almacén etiquetado con un código que ya no
 *     existe.
 *  2. **La ubicación de recepción no se desactiva.** Toda mercancía recibida pasa
 *     por ella (ADR-0009); sin ella, una recepción no tiene dónde aterrizar.
 *  3. **Una ubicación no cambia de bodega.** Su stock cambiaría de bodega sin
 *     ningún movimiento de inventario, y el descuadre no lo detectaría nadie.
 *
 * Referencia: docs/flujos/catalogo.md, docs/api/catalogo.md.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Longitudes que impone el esquema (`V3__inventory_domain.sql`). */
object CatalogLimits {
    const val PRODUCT_NAME = 120
    const val LOCATION_PART = 20
    const val WAREHOUSE_CODE = 20
    const val WAREHOUSE_NAME = 120
}

/**
 * Tipo de ubicación.
 *
 * `UNASSIGNED` no se declara por API: nace de la migración `V6` para el saldo
 * heredado del sistema anterior, del que se sabe que existe pero no dónde está.
 * Se vacía con el conteo físico (ADR-0012).
 */
enum class LocationKind {
    SHELF,
    RECEIVING,
    UNASSIGNED,
    ;

    /** La recepción es la única que no se puede desactivar. */
    val isDeactivatable: Boolean get() = this != RECEIVING

    companion object {
        /** Los únicos tipos que un cliente puede declarar al crear una ubicación. */
        val declarable: List<LocationKind> = listOf(SHELF, RECEIVING)

        fun parse(raw: String?): Result<LocationKind> = runCatching {
            val value = raw?.trim()?.uppercase().orEmpty()
            entries.firstOrNull { it.name == value && it in declarable }
                ?: throw IllegalArgumentException("Tipo de ubicación inválido: '$raw'")
        }
    }
}

/**
 * Una de las tres partes de una ubicación: pasillo, localidad y posición.
 *
 * **Se normaliza a mayúsculas, no se rechaza**: el legacy imprimía los códigos en
 * mayúsculas y el `CHECK` del esquema lo exige, así que un cliente que mande
 * `04`/`b`/`c` obtiene la misma ubicación que con `04`/`B`/`C` en lugar de un
 * error que no aporta nada. Es la diferencia deliberada con el código de
 * producto, donde una minúscula indica que algo cambió en el origen del dato y
 * por eso **se rechaza**.
 */
@JvmInline
value class LocationPart private constructor(val value: String) {

    companion object {
        private val ALLOWED = Regex("^[0-9A-ZÑ]+$")

        fun of(raw: String, field: String): DomainResult<LocationPart> {
            val value = raw.trim().uppercase()
            return when {
                value.isEmpty() ->
                    DomainError.InvalidLocationPart(field, raw, "no puede estar vacío").asLeft()
                value.length > CatalogLimits.LOCATION_PART ->
                    DomainError.InvalidLocationPart(field, raw, "excede ${CatalogLimits.LOCATION_PART} caracteres").asLeft()
                !ALLOWED.matches(value) ->
                    DomainError.InvalidLocationPart(field, raw, "solo admite 0-9, A-Z y Ñ").asLeft()
                else -> LocationPart(value).asRight()
            }
        }
    }

    override fun toString(): String = value
}

/** Una bodega. */
data class Warehouse(
    val id: Long,
    val code: String,
    val name: String,
    val address: String? = null,
    val isDefault: Boolean = false,
    val active: Boolean = true,
) {
    /**
     * Marca o desmarca esta bodega como la predeterminada.
     *
     * **Solo puede haber una**: el índice parcial `warehouses_one_default_idx` lo
     * impone, y por eso el caso de uso quita la marca anterior en la MISMA
     * transacción en que pone la nueva. Nunca quedan dos, ni siquiera un instante.
     */
    fun withDefault(value: Boolean): Warehouse = copy(isDefault = value)
}

/**
 * Una ubicación física.
 *
 * El nombre visible (`displayName`) lo **compone el servidor**, nunca el cliente:
 * si cambia el formato de las etiquetas de estante, no hay que tocar la API, la
 * web y el móvil a la vez (docs/api/catalogo.md).
 */
data class Location(
    val id: Long,
    val warehouseId: Long,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int = 1,
    val kind: LocationKind = LocationKind.SHELF,
    val active: Boolean = true,
) {
    /** `04-B-C` para un estante; `RECEPCION` para la recepción; `SIN-UBICAR` para el saldo heredado. */
    val displayName: String
        get() = when (kind) {
            LocationKind.RECEIVING -> code
            LocationKind.UNASSIGNED -> "SIN-UBICAR"
            LocationKind.SHELF -> "$hall-$code-$position"
        }

    /**
     * Reemplaza los campos editables.
     *
     * `warehouseId` no es editable: mover una ubicación de bodega cambiaría de
     * bodega el stock que contiene sin ningún movimiento de inventario.
     */
    fun withUpdated(
        hall: LocationPart,
        code: LocationPart,
        position: LocationPart,
        levels: Int,
        active: Boolean,
    ): DomainResult<Location> {
        if (levels < 1) return DomainError.InvalidLevels(levels).asLeft()
        val updated = copy(
            hall = hall.value,
            code = code.value,
            position = position.value,
            levels = levels,
            active = active,
        )
        return if (!updated.active && !updated.kind.isDeactivatable) {
            DomainError.ReceivingLocationProtected.asLeft()
        } else {
            updated.asRight()
        }
    }

    /** Desactivar no es borrar: el historial del kardex sigue apuntando a esta ubicación. */
    fun deactivate(): DomainResult<Location> = when {
        !active -> DomainError.LocationAlreadyInactive(code).asLeft()
        !kind.isDeactivatable -> DomainError.ReceivingLocationProtected.asLeft()
        else -> copy(active = false).asRight()
    }
}

/** Un producto. */
data class Product(
    val id: Long,
    val code: ProductCode,
    val name: String,
    val description: String? = null,
    val unitMeasureId: Long,
    val unitMeasure: String,
    val externalId: Long? = null,
    val active: Boolean = true,
) {
    /**
     * Reemplaza los campos editables.
     *
     * El `code` se recibe para tolerar el ida y vuelta de un cliente que hace
     * `GET`, modifica el nombre y devuelve el objeto entero; **si difiere del
     * actual, se rechaza**, en lugar de cambiar en silencio la clave de negocio.
     */
    fun withUpdated(
        code: ProductCode,
        name: String,
        description: String?,
        unitMeasureId: Long,
        unitMeasure: String,
        active: Boolean,
    ): DomainResult<Product> {
        if (code != this.code) return DomainError.ProductCodeImmutable(this.code.value).asLeft()
        if (name.isBlank()) {
            return DomainError.InvalidProductCode(code.value, "el nombre no puede estar vacío").asLeft()
        }
        if (name.length > CatalogLimits.PRODUCT_NAME) {
            return DomainError.InvalidProductCode(
                code.value,
                "el nombre excede ${CatalogLimits.PRODUCT_NAME} caracteres",
            ).asLeft()
        }
        return copy(
            name = name.trim(),
            description = description?.trim()?.ifBlank { null },
            unitMeasureId = unitMeasureId,
            unitMeasure = unitMeasure,
            active = active,
        ).asRight()
    }

    /** La baja es **lógica**: un producto inactivo conserva su kardex. */
    fun deactivate(): DomainResult<Product> =
        if (!active) DomainError.ProductAlreadyInactive(code.value).asLeft() else copy(active = false).asRight()
}

/** Una unidad de medida del catálogo cerrado. */
data class UnitOfMeasure(
    val id: Long,
    val code: String,
    val name: String,
    val allowsFraction: Boolean = false,
)
