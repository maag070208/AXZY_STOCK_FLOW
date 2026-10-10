package dev.axzy.stock.domain.error

import kotlinx.serialization.Serializable

/**
 * Domain errors.
 *
 * They are modeled as a sealed type instead of exceptions, for three reasons:
 *
 *  1. **They are part of the contract**, not an accident: "the quantity exceeds the
 *     invoice" is an expected business result, not a program failure.
 *  2. **They force you to handle them**: an exhaustive `when` over [DomainError] does not
 *     compile if a new case appears without being handled.
 *  3. **They can be mapped to HTTP codes** without losing information, and each one has a
 *     stable code that the mobile app and the web use to decide which
 *     message to show. A generic "access denied" is not actionable; "no
 *     you do not have permission over this warehouse" is.
 *
 * Reference: docs/historico/PLAN_ALMACEN_INTEGRAL.md §4 (error codes) and
 * docs/historico/PLAN_REFACTOR_MEJORAS.md R-20 (the legacy returned HTTP 200 on business errors and
 * 500 with body `{}`).
 */
@Serializable
sealed interface DomainError {

    /** Stable code for the client. It does not change between versions. */
    val code: String

    /** Readable message. It is a presentation detail; the client uses [code]. */
    val message: String

    // ── State transitions ──────────────────────────────────────────────────

    /**
     * The requested transition is not valid from the current state.
     * The legacy allowed illegal jumps because the states were free strings.
     */
    data class InvalidTransition(
        val entity: String,
        val from: String,
        val action: String,
        override val message: String = "Cannot '$action' from status '$from'",
    ) : DomainError {
        override val code = "INVALID_TRANSITION"
    }

    // ── Cantidades ──────────────────────────────────────────────────────────

    /** An attempt was made to locate or fulfill more than the line admits. */
    data class ExceedsLineQuantity(
        val requested: Long,
        val available: Long,
        override val message: String =
            "The requested quantity exceeds what is available on the line",
    ) : DomainError {
        override val code = "EXCEEDS_LINE_QUANTITY"
    }

    /**
     * The quantity and the units per box do not add up (decision R-80).
     * An invoice for 100 pieces with boxes of 30 is always wrongly captured data.
     */
    data class BoxesDoNotDivideEvenly(
        val quantity: Long,
        val unitsPerBox: Long,
        val remainder: Long,
        override val message: String =
            "The quantity is not divisible by units per box: " +
                "a remainder of $remainder would be left",
    ) : DomainError {
        override val code = "BOXES_DO_NOT_DIVIDE_EVENLY"
    }

    /**
     * The declared boxes do not fit in the declared quantity.
     * Example: 80 pieces with 2 boxes of 50 (that would be 100).
     */
    data class InconsistentBoxCount(
        val quantity: Long,
        val boxes: Long,
        val unitsPerBox: Long,
        override val message: String =
            "$boxes box(es) of $unitsPerBox do not fit in $quantity units",
    ) : DomainError {
        override val code = "INCONSISTENT_BOX_COUNT"
    }

    /** Negative or zero quantity where a positive one is required. */
    data class InvalidQuantity(
        override val message: String,
    ) : DomainError {
        override val code = "INVALID_QUANTITY"
    }

    // ── Discrepancies and approval ───────────────────────────────────────────

    /**
     * The line has an unapproved discrepancy.
     *
     * It is the error that makes rule B1 effective: **goods cannot be located
     * whose difference from the invoice has not been authorized**.
     * In the legacy this control did not exist.
     */
    data class DiscrepancyNotApproved(
        val difference: Long,
        override val message: String =
            "The line has a difference of $difference unapproved units. " +
                "A supervisor must authorize it before the goods are located.",
    ) : DomainError {
        override val code = "DISCREPANCY_NOT_APPROVED"
    }

    /**
     * Whoever counted tries to approve their own discrepancy (decision R-74).
     *
     * It is the most important internal control of the receiving flow: it prevents
     * someone from covering up their own counting error.
     */
    data class SegregationOfDuties(
        override val message: String =
            "You cannot approve a discrepancy you counted yourself. " +
                "Requires another person's authorization.",
    ) : DomainError {
        override val code = "SEGREGATION_OF_DUTIES"
    }

    /** An attempt was made to approve something that is not in an approvable state. */
    data class NotPendingApproval(
        val currentStatus: String,
        override val message: String =
            "The line is not awaiting approval (current status: $currentStatus)",
    ) : DomainError {
        override val code = "NOT_PENDING_APPROVAL"
    }

    /** A reason is required and was not provided (invariant I6). */
    data class ReasonRequired(
        val context: String,
        override val message: String = "A reason is required for $context",
    ) : DomainError {
        override val code = "REASON_REQUIRED"
    }

    // ── Inventario ──────────────────────────────────────────────────────────

    /** The operation would leave negative stock (invariant I2). */
    data class InsufficientStock(
        val available: Long,
        val requested: Long,
        val location: String,
        override val message: String =
            "Insufficient stock at $location: there are $available and $requested are requested",
    ) : DomainError {
        override val code = "INSUFFICIENT_STOCK"
    }

    /**
     * The origin and the destination of a movement are the same.
     * In the legacy this was not validated and generated movements with no effect.
     */
    data class SameSourceAndDestination(
        val location: String,
        override val message: String = "The source and destination cannot be the same location",
    ) : DomainError {
        override val code = "SAME_SOURCE_AND_DESTINATION"
    }

    // ── Documentos ──────────────────────────────────────────────────────────

    /** The document has pending lines and cannot be closed. */
    data class HasPendingLines(
        val pendingCount: Int,
        override val message: String =
            "The document has $pendingCount unresolved line(s) and cannot be closed",
    ) : DomainError {
        override val code = "HAS_PENDING_LINES"
    }

    /**
     * An attempt was made to delete a document with an inventory effect.
     * It is not deleted: it is **cancelled** with a compensating movement (R-57 and R-68).
     */
    data class CannotDeleteWithInventoryEffect(
        override val message: String =
            "This document affects inventory and cannot be deleted: " +
                "must be voided with a compensating movement.",
    ) : DomainError {
        override val code = "CANNOT_DELETE_WITH_INVENTORY_EFFECT"
    }

    /** Uniqueness conflict (invoice number or order already exists). */
    data class Duplicate(
        val entity: String,
        val value: String,
        override val message: String = "$entity with value '$value' already exists",
    ) : DomainError {
        override val code = "DUPLICATE"
    }

    /** The resource does not exist. */
    data class NotFound(
        val entity: String,
        val id: String,
        override val message: String = "No $entity found with id '$id'",
    ) : DomainError {
        override val code = "NOT_FOUND"
    }

    // ── Access (detail in docs/historico/PLAN_ROLES_PERMISOS_POLITICAS.md) ────────────────

    /** RBAC is not enough: the user does not have the permission. */
    data class PermissionDenied(
        val permission: String,
        override val message: String = "You do not have permission for this action",
    ) : DomainError {
        override val code = "PERMISSION_DENIED"
    }

    /** Has the permission, but not for **that** specific resource. */
    data class ScopeInsufficient(
        val permission: String,
        val scope: String,
        override val message: String =
            "You do not have permission over this resource (scope: $scope)",
    ) : DomainError {
        override val code = "SCOPE_INSUFFICIENT"
    }

    /** An ABAC policy denied the action. */
    data class PolicyDenied(
        val policyName: String,
        override val message: String = "Denied by policy '$policyName'",
    ) : DomainError {
        override val code = "POLICY_DENIED"
    }

    /**
     * There are applicable policies and none `ALLOW` matched.
     * It is what makes ABAC raise the bar instead of lowering it.
     */
    data class PolicyRequired(
        override val message: String = "This action requires specific authorization",
    ) : DomainError {
        override val code = "POLICY_REQUIRED"
    }

    // ── Transfers between warehouses ─────────────────────────────────────────

    /**
     * A transfer with the same origin and destination warehouse.
     *
     * The database enforces it with two `CHECK`s (in `transfers` and in `stock_in_transit`),
     * and the domain checks it **before touching the database** so that it can return a
     * `422` that explains the field instead of a constraint error.
     *
     * **Why it really matters:** a transfer from a warehouse to itself is dispatched, the goods are
     * "in transit" toward the same place, and the total
     * of the inventory **does not change** — so no report detects it. The
     * goods disappear from the locations and reach nowhere, without
     * anything turning red.
     */
    data class SameWarehouse(
        override val message: String =
            "The transfer must go to a warehouse other than the origin.",
    ) : DomainError {
        override val code = "SAME_WAREHOUSE"
    }

    /**
     * A transfer without lines.
     *
     * Dispatching an empty transfer creates a visible debt in `in-transit` that
     * does not correspond to any goods: it appears in the transit report and never
     * can be closed usefully.
     */
    data class EmptyTransfer(
        override val message: String = "The transfer has no lines.",
    ) : DomainError {
        override val code = "EMPTY_TRANSFER"
    }

    // ── Order fulfillment ──────────────────────────────────────────────────

    /**
     * The order was already confirmed (contract §6, `ORDER_ALREADY_CONFIRMED`).
     *
     * **Confirming twice would deduct the stock twice**: the first leaves the lines with no
     * pending quantity and the second would subtract again. The product total would drop
     * double and the discrepancy would look like a sale.
     */
    data class OrderAlreadyConfirmed(
        val orderNumber: String,
        override val message: String =
            "La orden $orderNumber ya estaba confirmada. Confirmarla otra vez " +
                "would deduct the stock twice.",
    ) : DomainError {
        override val code = "ORDER_ALREADY_CONFIRMED"
    }

    /**
     * An attempt was made to export an order that has not been confirmed yet.
     *
     * Exporting to the ERP something that has not yet been fulfilled would send the external system
     * an outflow that does not exist.
     */
    data class OrderNotConverted(
        val orderNumber: String,
        override val message: String =
            "Order $orderNumber is not confirmed: there is nothing to export.",
    ) : DomainError {
        override val code = "ORDER_NOT_CONVERTED"
    }

    /**
     * Packing more than what remains pending on the line was requested.
     *
     * The database enforces it with `order_lines_supplied_not_exceeds`
     * (`supplied_quantity <= quantity`), but the domain checks it beforehand so as to
     * be able to say **how much remains**, which is what the operator needs to know.
     */
    data class ExceedsPendingQuantity(
        val requested: Long,
        val pending: Long,
        override val message: String =
            "Attempted to fulfill $requested units and only $pending remain pending " +
                "on the line.",
    ) : DomainError {
        override val code = "EXCEEDS_PENDING_QUANTITY"
    }

    // ── Etiquetas ───────────────────────────────────────────────────────────

    /**
     * A value that the template needs to print is missing.
     *
     * **It is not resolved by leaving the placeholder or substituting it with blank.** The first
     * prints `{{productCode}}` on the shelf label; the second
     * prints a label that **looks correct and is not**, with a gap that
     * nobody can tell apart from a badly designed template.
     */
    data class MissingLabelField(
        val fields: List<String>,
        override val message: String =
            "Missing data for the label: ${fields.joinToString(", ")}. " +
                "The template needs them, and incomplete labels are not printed.",
    ) : DomainError {
        override val code = "MISSING_LABEL_FIELD"
    }

    /**
     * A label that is already printed is not printed again on a retry.
     *
     * The contract requires it with `409` in `PATCH /v1/print-jobs/{id}`: if the
     * printer retries a call that already worked, the job is not marked twice
     * and a second label is not used up.
     */
    data class LabelAlreadyPrinted(
        val jobId: Long,
        override val message: String =
            "Print job $jobId is already marked as printed; " +
                "a retry must not print twice.",
    ) : DomainError {
        override val code = "LABEL_ALREADY_PRINTED"
    }

    // ── Catalog: products, locations and warehouses (M01) ──────────────────
    //
    // The codes are those of the closed list in docs/api/errores.md §1.3-bis:
    // none is invented here. A new code is added to the error catalog
    // BEFORE it is used, which is why this block is read together with that document.

    /** The product code does not meet the `0-9A-Z` alphabet or the length. */
    data class InvalidProductCode(
        val raw: String,
        val detail: String,
        override val message: String = "Invalid product code ('$raw'): $detail",
    ) : DomainError {
        override val code = "INVALID_PRODUCT_CODE"
    }

    /** The unit is not in the closed catalog (`units_of_measure`). */
    data class UnknownUnitMeasure(
        val unit: String,
        override val message: String =
            "Unknown unit of measure '$unit'. It must exist in the unit catalog.",
    ) : DomainError {
        override val code = "UNKNOWN_UNIT_MEASURE"
    }

    /** The number of levels of a location is not positive. */
    data class InvalidLevels(
        val levels: Int,
        override val message: String = "The number of levels must be at least 1, got $levels",
    ) : DomainError {
        override val code = "INVALID_LEVELS"
    }

    /** The location type is neither `SHELF` nor `RECEIVING`. */
    data class InvalidLocationKind(
        val raw: String,
        override val message: String = "Invalid location kind '$raw': it must be SHELF or RECEIVING",
    ) : DomainError {
        override val code = "INVALID_LOCATION_KIND"
    }

    /** One of the three parts of the location does not meet the canonical format. */
    data class InvalidLocationPart(
        val part: String,
        val raw: String,
        val detail: String,
        override val message: String = "Invalid location part '$part' ('$raw'): $detail",
    ) : DomainError {
        override val code = "INVALID_LOCATION_PART"
    }

    /** The referenced warehouse does not exist. */
    data class UnknownWarehouse(
        val id: Long,
        override val message: String = "Warehouse $id does not exist",
    ) : DomainError {
        override val code = "UNKNOWN_WAREHOUSE"
    }

    /**
     * The file's `external_id` is not numeric.
     *
     * **The row is rejected instead of being stored as null**: the `external_id` is the
     * cross-reference with the ERP, and losing it in silence leaves products that
     * cannot be reconciled later.
     */
    data class InvalidExternalId(
        val raw: String,
        override val message: String =
            "The external identifier '$raw' is not numeric; the reference to the ERP would be lost.",
    ) : DomainError {
        override val code = "IMPORT_INVALID_EXTERNAL_ID"
    }

    /** An attempt was made to change a product's code: it is printed on the labels. */
    data class ProductCodeImmutable(
        val productCode: String,
        override val message: String =
            "The product code '$productCode' cannot be changed: it is printed on labels and shelves.",
    ) : DomainError {
        override val code = "PRODUCT_CODE_IMMUTABLE"
    }

    /** An attempt was made to move a location to another warehouse: its stock would change warehouse without a movement. */
    data class WarehouseImmutable(
        val locationId: Long,
        override val message: String =
            "Location $locationId cannot be moved to another warehouse: its stock would change warehouse without a movement.",
    ) : DomainError {
        override val code = "WAREHOUSE_IMMUTABLE"
    }

    /** A product with that code already exists. */
    data class DuplicateProductCode(
        val productCode: String,
        override val message: String = "A product with the code '$productCode' already exists",
    ) : DomainError {
        override val code = "DUPLICATE_PRODUCT_CODE"
    }

    /** A product with that ERP identifier already exists. */
    data class DuplicateExternalId(
        val externalId: String,
        override val message: String = "A product with the external identifier '$externalId' already exists",
    ) : DomainError {
        override val code = "DUPLICATE_EXTERNAL_ID"
    }

    /** That location already exists in the warehouse. */
    data class DuplicateLocation(
        val hall: String,
        val locationCode: String,
        val position: String,
        override val message: String =
            "A location $hall-$locationCode-$position already exists in that warehouse",
    ) : DomainError {
        override val code = "DUPLICATE_LOCATION"
    }

    /** A warehouse with that code already exists. */
    data class DuplicateWarehouseCode(
        val warehouseCode: String,
        override val message: String = "A warehouse with the code '$warehouseCode' already exists",
    ) : DomainError {
        override val code = "DUPLICATE_WAREHOUSE_CODE"
    }

    /** The product was already deactivated: deactivating it again would erase the reason of the first. */
    data class ProductAlreadyInactive(
        val productCode: String,
        override val message: String = "El producto '$productCode' ya estaba desactivado",
    ) : DomainError {
        override val code = "PRODUCT_ALREADY_INACTIVE"
    }

    /** The location was already deactivated. */
    data class LocationAlreadyInactive(
        val locationCode: String,
        override val message: String = "Location '$locationCode' was already deactivated",
    ) : DomainError {
        override val code = "LOCATION_ALREADY_INACTIVE"
    }

    /**
     * The receiving location cannot be deactivated.
     *
     * All received goods pass through it (ADR-0009): deactivating it would leave
     * receipts with no place to land.
     */
    data object ReceivingLocationProtected : DomainError {
        override val code = "RECEIVING_LOCATION_PROTECTED"
        override val message =
            "The receiving location cannot be deactivated: all received goods pass through it."
    }

    /** The location still has stock. */
    data class LocationHasStock(
        val quantity: String,
        override val message: String =
            "The location holds $quantity units. Move or adjust them before deactivating it.",
    ) : DomainError {
        override val code = "LOCATION_HAS_STOCK"
    }

    /** The product still has stock. */
    data class ProductHasStock(
        val stock: String,
        override val message: String =
            "The product has $stock units. Adjust or transfer the stock before deactivating it.",
    ) : DomainError {
        override val code = "PRODUCT_HAS_STOCK"
    }

    /** The product does not exist or is inactive. */
    data class ProductNotFound(
        val reference: String,
        override val message: String = "No active product found for '$reference'",
    ) : DomainError {
        override val code = "PRODUCT_NOT_FOUND"
    }

    /** The location does not exist or is inactive. */
    data class LocationNotFound(
        val reference: String,
        override val message: String = "No active location found for '$reference'",
    ) : DomainError {
        override val code = "LOCATION_NOT_FOUND"
    }

    /**
     * The import file has invalid rows: **none is written**
     * (K1). The list of rows travels with the error, because without the row number
     * the user cannot correct the file.
     */
    data class ImportInvalidRows(
        val rows: List<RejectedRow>,
        override val message: String =
            "The file has ${rows.size} invalid row(s). No product was written.",
    ) : DomainError {
        override val code = "IMPORT_INVALID_ROWS"

        /** A rejected row, with the **physical** number of the file (the header is 1). */
        @Serializable
        data class RejectedRow(val rowNumber: Int, val reason: String)
    }
}
