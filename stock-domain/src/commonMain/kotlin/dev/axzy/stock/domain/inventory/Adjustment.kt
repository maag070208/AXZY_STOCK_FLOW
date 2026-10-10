package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * THE SINGLE INVENTORY ADJUSTMENT RULE
 *
 * **This is the piece that the legacy had duplicated four times.** Every flow that
 * needed to move stock for a reason other than a receipt or a fulfillment had its own
 * path:
 *
 *  · the invoice discrepancy subtracted in one place,
 *  · the fulfillment shortage subtracted in another,
 *  · the physical count corrected in a third,
 *  · and the damage report in a fourth.
 *
 * Four paths mean four chances to forget the reason, to not check the balance, or to not record
 * it in the ledger. **Here there is only one**, and the four origins are a field of the same
 * movement.
 *
 * Three rules that are imposed here and not in each endpoint:
 *
 *  1. **The reason is mandatory, always.** An adjustment without a reason is an audit hole:
 *     within a year nobody can explain why 200 pieces are missing.
 *     There is no exception, not even for the system.
 *
 *  2. **An adjustment that INCREASES the inventory requires approval.** A shrinkage
 *     is detected by itself in the next count; an unjustified increase is the simplest way
 *     to cover up a theft, so it needs someone else to sign it.
 *
 *  3. **Whoever adjusts does not approve their own** (segregation of duties, decision
 *     R-74). It is the internal control that prevents a person from covering their own
 *     error without anyone reviewing it.
 *
 * Reference: docs/historico/PLAN_ALMACEN_INTEGRAL.md §2.9 and decisions B2, B3 and R-74.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Why the inventory is adjusted.
 *
 * **Closed list with operational meaning.** The legacy stored the reason as
 * free text, so "shrinkage", "Shrinkage" and "damaged product" coexisted as three
 * different reasons, and no report could add them up.
 */
enum class AdjustmentReason(val displayName: String, val description: String) {
    SHORTAGE(
        "Faltante",
        "Goods are missing compared to what should be there. Detected during a count.",
    ),
    DAMAGE(
        "Damage",
        "The goods were damaged and cannot be sold. They are withdrawn from available stock.",
    ),
    THEFT(
        "Robo",
        "A shortage with signs of theft was detected. **Requires a written reason**: " +
            "it has legal implications and someone will have to answer for it.",
    ),
    SUPPLIER_ERROR(
        "Supplier error",
        "The supplier sent less or something different from what was invoiced.",
    ),
    COUNT_ERROR(
        "Count error",
        "The previous balance was wrong because of a capture error, not a real loss.",
    ),
    RETURN_TO_SUPPLIER(
        "Return to supplier",
        "The goods leave the warehouse back to the supplier.",
    ),
    EXPIRED(
        "Caducado",
        "The goods reached the end of their useful life and are withdrawn.",
    ),
    OTHER(
        "Otro",
        "**Requires a written reason.** If it is used often, a reason is missing from the " +
            "list: a repeated 'other' is a sign that the taxonomy is wrong.",
    ),
    ;

    /** Indicates whether the reason requires writing an explanation in addition to choosing it. */
    val requiresNote: Boolean get() = this == THEFT || this == OTHER
}

/**
 * Where the adjustment comes from.
 *
 * It is what allows answering "how much was adjusted for the physical count and how much for
 * damage?" without interpreting texts. The four origins of the legacy, unified here.
 */
enum class AdjustmentOrigin(val displayName: String) {
    INVOICE_DISCREPANCY("Invoice discrepancy"),
    FULFILLMENT_SHORTAGE("Shortage in fulfillment"),
    MANUAL_COUNT("Physical count"),
    DAMAGE_REPORT("Damage report"),
    ;
}

/** Where the adjustment goes. */
enum class AdjustmentDirection {
    /** Increases the inventory. Requires approval. */
    INCREASE,

    /** Decreases the inventory. */
    DECREASE,
    ;

    /**
     * Translates into a ledger movement.
     *
     * **The adjustment does not invent a new type**: it uses `ADJUSTMENT_IN` or
     * `ADJUSTMENT_OUT`, which already exist in the movement→effect matrix with their
     * tests. A new type would force declaring its effect everywhere.
     */
    val movementType: MovementType
        get() = when (this) {
            INCREASE -> MovementType.ADJUSTMENT_IN
            DECREASE -> MovementType.ADJUSTMENT_OUT
        }
}

/**
 * An adjustment request, already validated.
 *
 * It is built only by [AdjustmentRequest.create], so **it is impossible for a request to
 * exist without a reason**: the type does not allow it.
 */
data class AdjustmentRequest private constructor(
    val productCode: ProductCode,
    /**
     * The location, by its **visible name** (`04-B-C`, `RECEIVING`, `UNASSIGNED`).
     *
     * It is not a [dev.axzy.stock.value.LocationCode]: that value object is the legacy's
     * flat code (`04BCA`) and cannot represent either the receiving location or the
     * unassigned balance. Here it is only needed to name the location in a message, and
     * the visible name is the one the operator recognizes.
     */
    val locationLabel: String,
    val quantity: Quantity,
    val direction: AdjustmentDirection,
    val reason: AdjustmentReason,
    val origin: AdjustmentOrigin,
    /** Required for [AdjustmentReason.THEFT] and [AdjustmentReason.OTHER]. */
    val note: String?,
    /** Who requests it. Necessary for segregation of duties. */
    val requestedBy: Long,
    /** The mobile app's idempotency key, so that a retry does not duplicate. */
    val idempotencyKey: String?,
) {
    /** The ledger movement that corresponds to this adjustment. */
    val movementType: MovementType get() = direction.movementType

    /**
     * Indicates whether this adjustment needs another person to approve it.
     *
     * **Only increases.** A shrinkage is detected by itself in the next count; an
     * unjustified increase is the simplest way to cover up a theft, so it
     * needs a second signature. That a shortage does not require approval is
     * deliberate: blocking the correction of an obvious error only makes
     * people stop correcting them.
     */
    val requiresApproval: Boolean get() = direction == AdjustmentDirection.INCREASE

    companion object {
        /** Minimum length of the written reason, when it is required. */
        const val MIN_NOTE_LENGTH = 10

        /**
         * Creates a validated request.
         *
         * @return the request, or the domain error that explains what is missing.
         */
        fun create(
            productCode: ProductCode,
            locationLabel: String,
            quantity: Quantity,
            direction: AdjustmentDirection,
            reason: AdjustmentReason,
            origin: AdjustmentOrigin,
            note: String?,
            requestedBy: Long,
            idempotencyKey: String? = null,
        ): DomainResult<AdjustmentRequest> {
            // ── The quantity must be POSITIVE, not just non-zero ───────────────
            //
            // **Checking `isZero` is NOT enough, and it was a real hole.** With a NEGATIVE quantity and
            // direction DECREASE, the movement subtracts a negative number,
            // that is, **ADDS inventory**, and also `requiresApproval` would be `false`
            // because it looks at the DIRECTION. Result: stock can be increased without
            // anyone's approval, which is exactly what decision R-74 forbids.
            // pretende impedir.
            //
            // It was found by an agent reading this class: `Quantity.ofScaled` does not validate
            // the sign, so the negative value reached this point. It is validated in the
            // TYPE and not in the endpoint: if it depended on each route remembering, the
            // next route added would have the same hole.
            if (quantity.isZero || quantity.isNegative) {
                return DomainError.InvalidQuantity(
                    "The quantity of an adjustment must be greater than zero. " +
                        "The direction already says whether it adds or subtracts: a negative quantity " +
                        "would reverse the movement and increase inventory without " +
                        "need approval.",
                ).asLeft()
            }

            // ── Written reason when the reason requires it ────────────────────
            if (reason.requiresNote) {
                val text = note?.trim().orEmpty()
                if (text.length < MIN_NOTE_LENGTH) {
                    return DomainError.ReasonRequired(
                        "The reason «${reason.displayName}» requires an explanation of at " +
                            "less than $MIN_NOTE_LENGTH characters. Without it, the adjustment does not " +
                            "it can be audited within a year.",
                    ).asLeft()
                }
            }

            // ── Whoever requests must be identified ─────────────────────────
            // Without this, segregation of duties cannot be checked nor
            // the audit log can record who moved the inventory.
            if (requestedBy <= 0) {
                return DomainError.PermissionDenied(
                    "An inventory adjustment requires an identified user. " +
                        "Without it there is no segregation of duties or audit.",
                ).asLeft()
            }

            return AdjustmentRequest(
                productCode = productCode,
                locationLabel = locationLabel,
                quantity = quantity,
                direction = direction,
                reason = reason,
                origin = origin,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                requestedBy = requestedBy,
                idempotencyKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() },
            ).asRight()
        }
    }
}

/**
 * Decides whether an adjustment needs approval and who can give it.
 *
 * **Pure function, no database.** It is the part of the rule that can be tested
 * exhaustively in milliseconds, and which decides whether the movement is written right away.
 * o queda pendiente.
 */
object AdjustmentPolicy {

    /**
     * What to do with the request.
     *
     * The three cases are different and the caller has to be able to tell them apart:
     * write the movement, leave it pending or reject it.
     */
    sealed interface Decision {
        /** The movement can be written now. */
        data object Apply : Decision

        /**
         * It stays pending approval.
         *
         * **Nothing is written to the ledger yet.** A movement that awaits
         * approval and has already moved the inventory would be a stock change without
         * authorization, which is exactly what approval is meant to prevent.
         */
        data class PendingApproval(val reason: String) : Decision

        /** Rejected. */
        data class Reject(val error: DomainError) : Decision
    }

    /**
     * Decides what to do.
     *
     * @param request the request, already validated.
     * @param approverId who would approve, if needed. `null` if nobody.
     * @param available the balance available at the location. `null` if it is unknown.
     */
    fun decide(
        request: AdjustmentRequest,
        approverId: Long? = null,
        available: Quantity? = null,
    ): Decision {

        // ── A decrease cannot leave the balance negative ─────────────────
        // It is checked here to give a useful message, although the database also
        // prevents it with a CHECK. The CHECK says "stock_non_negative"; this message
        // says how much there is and how much is to be removed.
        if (request.direction == AdjustmentDirection.DECREASE && available != null) {
            if (request.quantity > available) {
                return Decision.Reject(
                    // The error carries the NUMBERS and the location, not just a
                    // message: an "insufficient stock" without quantities forces
                    // looking at the database to know what happened.
                    DomainError.InsufficientStock(
                        available = available.units,
                        requested = request.quantity.units,
                        location = request.locationLabel,
                    ),
                )
            }
        }

        // ── No approval required: it is applied ─────────────────────────────
        if (!request.requiresApproval) return Decision.Apply

        // ── Requires approval and there is no approver ──────────────────────
        if (approverId == null) {
            return Decision.PendingApproval(
                "An adjustment that INCREASES inventory needs another person's approval " +
                    "person. A shrinkage is detected on its own at the next count; a " +
                    "an unjustified increase is the simplest way to cover up a theft.",
            )
        }

        // ── The approver cannot be the one requesting ───────────────────────
        // Decision R-74. It is the control that prevents someone from covering their own
        // error without anyone reviewing it.
        if (approverId == request.requestedBy) {
            return Decision.Reject(
                DomainError.SegregationOfDuties(
                    "Whoever requests an adjustment cannot approve it. The user " +
                        "${request.requestedBy} would have to approve their own request.",
                ),
            )
        }

        return Decision.Apply
    }
}
