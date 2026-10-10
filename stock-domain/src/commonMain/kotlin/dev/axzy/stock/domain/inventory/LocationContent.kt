package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONTENT OF A LOCATION — TWO IMPLEMENTATIONS, ON PURPOSE
 *
 * Two ways of computing how much there is in a location coexist here:
 *
 *  · [LocationContent.direct] — the content is a **direct query** to the balance of
 *    the location. It is the model of the new system.
 *
 *  · [LocationContent.legacyAlgorithm] — it reproduces **the legacy algorithm**
 *    (`getLocationContent`), with its historical semantics: if the last
 *    movement is an adjustment, the total is that adjustment; otherwise it adds the `LOCATION`s
 *    and subtracts the `OUT`s since the last adjustment.
 *
 * **Why keep the old algorithm if it is wrong?** Because the plan requires a decision
 * made with data, not intuition (finding R-67):
 *
 *   > *"It is decided with the data in front, and it is frozen with characterization tests
 *   > before touching it."*
 *
 * The new algorithm depends on `LOCATION` **not** subtracting from the total, and the old
 * one depends on it **doing** subtract. If we simplified blindly, the historical reports would
 * change without anyone knowing why.
 *
 * With both implementations, [LocationContentComparison] can be run, which
 * **quantifies the difference** over real data and explains it. When the
 * difference is entirely attributable to the legacy bug, it is simplified with
 * peace of mind; if not, there is a business rule we did not see.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object LocationContent {

    /**
     * **The model of the new system.**
     *
     * The content of a location is simply what is there. There is no
     * calculation: the balance is already in the ledger, maintained by the transactions.
     *
     * It is correct by construction, and that is why it is O(1): it does not walk the ledger.
     */
    fun direct(
        ledger: InventoryLedger,
        productCode: String,
        location: LocationCode,
    ): Quantity = ledger.quantityAt(productCode, location)

    /**
     * **The legacy algorithm**, reproduced as is.
     *
     * What it is for: to compare. Its output allows measuring how far the old system
     * deviates from the new one, and that figure is the input for decision R-67
     * and for the discrepancy report of the migration (Phase 7).
     *
     * Rules it reproduces, in the same order as the original:
     *
     *  1. The entries of the location are walked in **descending** order and
     *     the **last** one of the product is taken: earlier movements of the
     *     same product are ignored.
     *  2. If that last entry is an `ADJUSTMENT`, the total is **its quantity**.
     *  3. Otherwise the last `ADJUSTMENT` of the product in that location is looked up and:
     *     · the `LOCATION`s **after** that adjustment (including it) are **added**;
     *     · the `OUT`s after it are **subtracted**.
     *  4. If there is no adjustment, **all** the `LOCATION`s are added and
     *     **all** the `OUT`s are subtracted.
     *  5. Totals less than or equal to zero are discarded.
     *
     * **The defect is in step 4 and in the definition of `LOCATION`:** the
     * algorithm treats the location as the result of its own movements,
     * instead of reading the balance. Combined with the fact that in the legacy `LOCATION`
     * subtracted from the total and also mutated the receiving entry, it produced the
     * descuadre acumulado.
     */
    fun legacyAlgorithm(
        ledger: InventoryLedger,
        productCode: String,
        location: LocationCode,
    ): Quantity {
        val locationEntries = ledger.entries
            .filter { it.productCode == productCode && (it.to == location || it.from == location) }

        if (locationEntries.isEmpty()) return Quantity.ZERO

        // Step 1: the product's last entry in that location.
        val last = locationEntries.last()

        // Step 2: if the last one is an adjustment, the total is that adjustment.
        if (last.type == MovementType.ADJUSTMENT_IN || last.type == MovementType.ADJUSTMENT_OUT) {
            return adjustmentTotal(last)
        }

        // Step 3: look up the last earlier adjustment.
        val lastAdjustment = locationEntries.lastOrNull {
            it.type == MovementType.ADJUSTMENT_IN || it.type == MovementType.ADJUSTMENT_OUT
        }

        val fromSequence = lastAdjustment?.sequence ?: 0L

        // Step 4: add LOCATION and subtract OUT since the adjustment (or since the start).
        val sum = locationEntries
            .filter { it.sequence >= fromSequence }
            .filter {
                it.type == MovementType.LOCATE ||
                    it.type == MovementType.MOVE_WITHIN_WAREHOUSE ||
                    it.type == MovementType.TRANSFER_IN ||
                    it.type == MovementType.RECEIPT_IN ||
                    it.type == MovementType.INITIAL
            }
            .sumOf { it.quantity.value }

        val subtraction = locationEntries
            .filter { it.sequence >= fromSequence }
            .filter {
                it.type == MovementType.FULFILLMENT_OUT ||
                    it.type == MovementType.ADJUSTMENT_OUT ||
                    it.type == MovementType.TRANSFER_OUT
            }
            .sumOf { it.quantity.value }

        val total = Quantity.ofScaled(sum - subtraction)

        // Step 5: totals less than or equal to zero are discarded.
        return if (total.isPositive) total else Quantity.ZERO
    }

    private fun adjustmentTotal(entry: LedgerEntry): Quantity =
        if (entry.type == MovementType.ADJUSTMENT_OUT) Quantity.ZERO else entry.quantity
}

/**
 * Result of comparing the two methods over a specific location.
 * It is the input of the discrepancy report of the migration (Phase 7).
 */
data class ContentDivergence(
    val productCode: String,
    val location: LocationCode,
    /** What the new model says (the real balance). */
    val modelSays: Quantity,
    /** What the legacy algorithm would say. */
    val legacySays: Quantity,
) {
    val difference: Quantity get() = modelSays - legacySays
    val isNegativeDifference: Boolean get() = difference.isNegative
    val absoluteDifference: Long get() = abs(difference.value)

    /**
     * Indicates whether the divergence is **explained by the double-deduction bug**.
     *
     * When the legacy located goods, it deducted from the total. That is why its
     * algorithm tends to report **less** than there is, and the difference
     * should be positive (the new model sees more stock). A divergence
     * **negative** is not explained by the bug: it points to another cause and deserves
     * manual review.
     */
    val explainedByKnownBug: Boolean
        get() = difference.isPositive || difference.isZero
}

/**
 * Compares the two methods over a whole ledger and returns the divergences.
 *
 * **How it is used in the migration:** a real history from the legacy is loaded into the
 * model, this is run, and the result is the report presented to the business before
 * cutover. If all the divergences are positive, the discrepancy is the known bug and
 * reconciliation is direct. If there are negative ones, one has to investigate before
 * touching anything.
 */
object LocationContentComparison {

    fun compare(ledger: InventoryLedger): List<ContentDivergence> =
        ledger.allBalances()
            .filter { it.quantity.isPositive }
            .map { balance ->
                ContentDivergence(
                    productCode = balance.productCode,
                    location = balance.location,
                    modelSays = LocationContent.direct(ledger, balance.productCode, balance.location),
                    legacySays = LocationContent.legacyAlgorithm(
                        ledger, balance.productCode, balance.location,
                    ),
                )
            }

    /** Only the locations where the two methods disagree. */
    fun onlyDivergent(ledger: InventoryLedger): List<ContentDivergence> =
        compare(ledger).filter { it.difference.value != 0L }

    /**
     * Summary for the report. It returns a readable text, not a structure:
     * the goal is that someone in the business can read it and decide.
     */
    fun report(ledger: InventoryLedger): String {
        val divergences = onlyDivergent(ledger)
        if (divergences.isEmpty()) {
            return "No discrepancies: the two methods agree on the " +
                "${compare(ledger).size} locations with stock."
        }

        val explained = divergences.count { it.explainedByKnownBug }
        val withoutExplanation = divergences.filterNot { it.explainedByKnownBug }
        val mismatchTotal = divergences.sumOf { it.absoluteDifference }

        return buildString {
            appendLine("Content discrepancy report by location")
            appendLine("─────────────────────────────────────────────────────")
            appendLine("Locations with discrepancy: ${divergences.size}")
            appendLine("  · Explained by the known bug (the model sees MORE): $explained")
            appendLine("  · Not explained (the model sees LESS): ${withoutExplanation.size}")
            appendLine("Descuadre absoluto acumulado: ${Quantity.ofScaled(mismatchTotal)} unidades")
            if (withoutExplanation.isNotEmpty()) {
                appendLine()
                appendLine("⚠️  The following are NOT explained by the known bug and require ")
                appendLine("    manual review before simplifying the algorithm:")
                withoutExplanation.take(20).forEach {
                    appendLine(
                        "  · ${it.productCode} in ${it.location.value}: " +
                            "modelo=${it.modelSays} legacy=${it.legacySays}"
                    )
                }
                if (withoutExplanation.size > 20) {
                    appendLine("    … and ${withoutExplanation.size - 20} more")
                }
            }
        }
    }
}
