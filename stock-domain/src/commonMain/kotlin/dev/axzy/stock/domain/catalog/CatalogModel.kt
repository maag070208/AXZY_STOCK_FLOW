package dev.axzy.stock.domain.catalog

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.ProductCode

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CATALOG — products, warehouses and locations (M01)
 *
 * The rules that govern the master source of everything else. They are tested without
 * a database: they are the ones that decide whether an operation is admissible, and the database
 * only enforces them (unique indexes and `CHECK`), it never replaces them.
 *
 * Three rules that are not about form but about business, and that is why they are here:
 *
 *  1. **The product code is not changed.** It is printed on the labels and on
 *     the shelves: changing it leaves the warehouse labeled with a code that no longer
 *     existe.
 *  2. **The receiving location is not deactivated.** All received goods pass through
 *     it (ADR-0009); without it, a receipt has nowhere to land.
 *  3. **A location does not change warehouse.** Its stock would change warehouse without
 *     any inventory movement, and nobody would detect the discrepancy.
 *
 * Referencia: docs/flujos/catalogo.md, docs/api/catalogo.md.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Lengths the schema imposes (`V3__inventory_domain.sql`). */
object CatalogLimits {
    const val PRODUCT_NAME = 120
    const val LOCATION_PART = 20
    const val WAREHOUSE_CODE = 20
    const val WAREHOUSE_NAME = 120
}

/**
 * Location type.
 *
 * `UNASSIGNED` is not declared through the API: it comes from migration `V6` for the inherited
 * balance of the previous system, which is known to exist but not where it is.
 * It is emptied by the physical count (ADR-0012).
 */
enum class LocationKind {
    SHELF,
    RECEIVING,
    UNASSIGNED,
    ;

    /** Receiving is the only one that cannot be deactivated. */
    val isDeactivatable: Boolean get() = this != RECEIVING

    companion object {
        /** The only types a client can declare when creating a location. */
        val declarable: List<LocationKind> = listOf(SHELF, RECEIVING)

        fun parse(raw: String?): Result<LocationKind> = runCatching {
            val value = raw?.trim()?.uppercase().orEmpty()
            entries.firstOrNull { it.name == value && it in declarable }
                ?: throw IllegalArgumentException("Invalid location kind: '$raw'")
        }
    }
}

/**
 * One of the three parts of a location: hall, code and position.
 *
 * **It is normalized to uppercase, not rejected**: the legacy printed the codes in
 * uppercase and the schema's `CHECK` requires it, so a client that sends
 * `04`/`b`/`c` gets the same location as `04`/`B`/`C` instead of
 * an error that adds nothing. It is the deliberate difference from the product code,
 * product code, where a lowercase letter indicates that something changed at the origin of the
 * data, and that is why it **is rejected**.
 */
@kotlin.jvm.JvmInline
value class LocationPart private constructor(val value: String) {

    companion object {
        private val ALLOWED = Regex("^[0-9A-ZÑ]+$")

        fun of(raw: String, field: String): DomainResult<LocationPart> {
            val value = raw.trim().uppercase()
            return when {
                value.isEmpty() ->
                    DomainError.InvalidLocationPart(field, raw, "cannot be empty").asLeft()
                value.length > CatalogLimits.LOCATION_PART ->
                    DomainError.InvalidLocationPart(field, raw, "excede ${CatalogLimits.LOCATION_PART} caracteres").asLeft()
                !ALLOWED.matches(value) ->
                    DomainError.InvalidLocationPart(field, raw, "only allows 0-9, A-Z and Ñ").asLeft()
                else -> LocationPart(value).asRight()
            }
        }
    }

    override fun toString(): String = value
}

/** A warehouse. */
data class Warehouse(
    val id: Long,
    val code: String,
    val name: String,
    val address: String? = null,
    val isDefault: Boolean = false,
    val active: Boolean = true,
) {
    /**
     * Sets or clears this warehouse as the default.
     *
     * **There can only be one**: the partial index `warehouses_one_default_idx` enforces
     * it, and that is why the use case removes the previous flag in the SAME
     * transaction in which it sets the new one. Never two remain, not even for an instant.
     */
    fun withDefault(value: Boolean): Warehouse = copy(isDefault = value)
}

/**
 * A physical location.
 *
 * The visible name (`displayName`) is **composed by the server**, never by the client:
 * if the format of the shelf labels changes, there is no need to touch the API, the
 * web and the mobile app at the same time (docs/api/catalogo.md).
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
    /** `04-B-C` for a shelf; `RECEIVING` for receiving; `UNASSIGNED` for the inherited balance. */
    val displayName: String
        get() = when (kind) {
            LocationKind.RECEIVING -> code
            LocationKind.UNASSIGNED -> "UNASSIGNED"
            LocationKind.SHELF -> "$hall-$code-$position"
        }

    /**
     * Replaces the editable fields.
     *
     * `warehouseId` is not editable: moving a location to another warehouse would change
     * the warehouse of the stock it contains without any inventory movement.
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

    /** Deactivating is not deleting: the ledger's history still points to this location. */
    fun deactivate(): DomainResult<Location> = when {
        !active -> DomainError.LocationAlreadyInactive(code).asLeft()
        !kind.isDeactivatable -> DomainError.ReceivingLocationProtected.asLeft()
        else -> copy(active = false).asRight()
    }
}

/** A product. */
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
     * Replaces the editable fields.
     *
     * The `code` is received to tolerate a client that does a `GET`, changes the name and
     * returns the whole object; **if it differs from the current one, it is rejected**,
     * instead of silently changing the business key.
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
            return DomainError.InvalidProductCode(code.value, "the name cannot be empty").asLeft()
        }
        if (name.length > CatalogLimits.PRODUCT_NAME) {
            return DomainError.InvalidProductCode(
                code.value,
                "the name exceeds ${CatalogLimits.PRODUCT_NAME} characters",
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

    /** The deletion is **logical**: an inactive product keeps its ledger. */
    fun deactivate(): DomainResult<Product> =
        if (!active) DomainError.ProductAlreadyInactive(code.value).asLeft() else copy(active = false).asRight()
}

/** A unit of measure from the closed catalog. */
data class UnitOfMeasure(
    val id: Long,
    val code: String,
    val name: String,
    val allowsFraction: Boolean = false,
)
