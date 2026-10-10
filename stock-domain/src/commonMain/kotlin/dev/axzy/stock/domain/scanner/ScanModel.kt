package dev.axzy.stock.domain.scanner

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BARCODE SCANNER — shared model
 *
 * It resolves the **two defects of the legacy scanner**, and both are of
 * pure logic, so they are resolved here and not in each platform:
 *
 *  · **M4 / misaligned window.** The legacy visual mask measured 300×100
 *    pixels, but the validation by `e.bounds` accepted only 250×50 centered.
 *    Result: **valid scans were discarded** and operators repeating the
 *    gesture without understanding why.
 *
 *    The root cause is that there were **two definitions** of the same window, in two
 *    different files, and nothing kept them in sync. Here there is a single
 *    constant ([ScanWindow.DEFAULT]) that both the mask and the validation use. With
 *    a single source, the misalignment is impossible.
 *
 *  · **M3 / broken state machine.** The legacy used a loose numeric `step`.
 *    `step` was incremented once and the handler compared `step === 1`, so
 *    the branch that assigns the location **was unreachable** until an error
 *    set it back to 0. The operator needed a third scan.
 *
 *    Here the state is a sealed type: **invalid transitions cannot be
 *    written**. There is no number that can be compared wrongly.
 *
 * **This model does NOT access the camera.** The camera is platform code
 * (`expect/actual` with CameraX + ML Kit on Android and AVFoundation + Vision on
 * iOS), but **what to do with what was scanned is the same** on both.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Rectangle in normalized coordinates (0.0 to 1.0) of the frame.
 *
 * **Normalized on purpose:** the camera delivers pixels and the screens have different
 * densities. With pixels, the same logical window would measure different things on a
 * phone and on a tablet, and the legacy validation already
 * failed by working with absolute pixels.
 */
data class ScanRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    init {
        require(left in 0.0..1.0) { "left must be between 0 and 1: $left" }
        require(top in 0.0..1.0) { "top must be between 0 and 1: $top" }
        require(width > 0.0 && left + width <= 1.0001) {
            "The window exceeds the width: left=$left width=$width"
        }
        require(height > 0.0 && top + height <= 1.0001) {
            "The window exceeds the height: top=$top height=$height"
        }
    }

    val right: Double get() = left + width
    val bottom: Double get() = top + height
    val centerX: Double get() = left + width / 2
    val centerY: Double get() = top + height / 2
}

/**
 * The scan window. **A single definition for the mask and the validation.**
 *
 * The legacy had a 300×100 mask and a 250×50 validation. Here the mask draws `window` and the
 * validation checks `window`: physically they cannot differ.
 * pueden discrepar.
 */
data class ScanWindow(
    /** The area where the code must fall to be accepted. */
    val window: ScanRect,
    /**
     * Tolerance margin around the window, as a fraction of the frame.
     *
     * It exists because an operator with a shaky hand should not have to repeat the
     * scan. The legacy had ZERO tolerance outside the window, which
     * combined with the misalignment made scanning frustrating.
     */
    val tolerance: Double = 0.05,
) {
    init {
        require(tolerance in 0.0..0.5) { "The tolerance must be between 0 and 0.5: $tolerance" }
    }

    /** The effective acceptance window: the visual one plus the tolerance. */
    val effective: ScanRect
        get() = ScanRect(
            left = (window.left - tolerance).coerceAtLeast(0.0),
            top = (window.top - tolerance).coerceAtLeast(0.0),
            width = (window.width + tolerance * 2).coerceAtMost(1.0 - (window.left - tolerance).coerceAtLeast(0.0)),
            height = (window.height + tolerance * 2).coerceAtMost(1.0 - (window.top - tolerance).coerceAtLeast(0.0)),
        )

    companion object {
        /**
         * The default window: a horizontal band centered in the upper-middle third of the
         * frame.
         *
         * It is **a shared constant**. The visual mask and the validation read it from here, so
         * they cannot be misaligned.
         */
        val DEFAULT = ScanWindow(
            window = ScanRect(left = 0.10, top = 0.35, width = 0.80, height = 0.20),
            tolerance = 0.05,
        )
    }
}

/**
 * Checks whether the center of a detected code falls inside the window.
 *
 * **The CENTER is used and not the full rectangle.** The legacy checked the origin of the bounding
 * box (`y < top + 50`), so a tall code stayed out
 * even though it was visually centered. The center is what the operator
 * perceives as "I put it on the line".
 */
fun ScanWindow.contains(bounds: ScanRect): Boolean {
    val effective = effective
    val insideX = bounds.centerX in effective.left..effective.right
    val insideY = bounds.centerY in effective.top..effective.bottom
    return insideX && insideY
}

/** What the scanner must do at each step. */
enum class ScanTarget(val displayName: String) {
    PRODUCT("a product code"),
    LOCATION("a location"),
    CONTAINER("a container"),
    ;

    /** Context hint to show the operator. */
    fun instruction(step: Int): String = "Escanea $displayName (paso $step)"
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * THE SCANNER STATE MACHINE
 *
 * Replaces the legacy's numeric `step`. **Invalid transitions cannot be
 * expressed**: there is no way to assign a location if the product has not yet been
 * scanned, because the state does not carry the product.
 * ═══════════════════════════════════════════════════════════════════════════
 */
sealed interface ScanState {

    /** What is expected next, to show the correct instruction. */
    val expected: ScanTarget

    /** Step number, only for the interface. **Never for logic.** */
    val step: Int

    /** Nothing has been scanned yet. */
    data object Idle : ScanState {
        override val expected = ScanTarget.PRODUCT
        override val step = 1
    }

    /** The product was scanned and the units are expected. */
    data class ProductScanned(
        val product: ProductCode,
        /** Units to move. `null` while the operator has not entered them. */
        val units: Long? = null,
    ) : ScanState {
        override val expected = ScanTarget.LOCATION
        override val step = if (units == null) 2 else 3
    }

    /** Product, units and destination location: ready to confirm. */
    data class ReadyToConfirm(
        val product: ProductCode,
        val units: Long,
        val to: LocationCode,
    ) : ScanState {
        override val expected = ScanTarget.LOCATION
        override val step = 4
    }

    /** Recoverable error: the reason is shown and it can be retried. */
    data class Failed(
        val reason: String,
        /** The state to return to when retrying. */
        val retryFrom: ScanState,
    ) : ScanState {
        override val expected: ScanTarget get() = retryFrom.expected
        override val step: Int get() = retryFrom.step
    }
}

/** What was scanned, already normalized. */
data class ScanInput(val raw: String, val bounds: ScanRect)

/** Result of processing a scan. */
sealed interface ScanOutcome {
    data class Advanced(val state: ScanState) : ScanOutcome
    data class Rejected(val reason: String) : ScanOutcome
}

/**
 * Processes a scan according to the current state.
 *
 * **No `when` over numbers**: the `when` is over the state type, so the
 * compiler guarantees that no case is missing. That is the M3 defect resolved
 * by construction, not by care.
 */
fun ScanState.onScan(
    input: ScanInput,
    window: ScanWindow = ScanWindow.DEFAULT,
    /** Resolves whether a code corresponds to a real product. */
    resolveProduct: (String) -> ProductCode?,
    /** Resolves whether a code corresponds to a real location. */
    resolveLocation: (String) -> LocationCode?,
    /** Units entered by the operator, for the intermediate step. */
    unitsEntered: Long? = null,
): ScanOutcome {
    // Block body, not an expression body: inside an expression `when` you cannot use
    // `return`, and here the early return makes the flow more readable
    // than nesting conditionals.
    return when (this) {

    is ScanState.Idle -> {
        if (!window.contains(input.bounds)) {
            return ScanOutcome.Rejected(
                "The code is outside the scanning window. Center it on the band."
            )
        }
        val product = resolveProduct(input.raw)
            ?: return ScanOutcome.Rejected("'${input.raw}' is not a known product code")
        ScanOutcome.Advanced(ScanState.ProductScanned(product, unitsEntered))
    }

    is ScanState.ProductScanned -> {
        // ── The rule the legacy could not express ────────────────────────
        // Here the product IS already in the state, so there is no way to assign a
        // location without having scanned the product first.
        // The legacy's `step === 1` allowed exactly the opposite.
        if (units == null && unitsEntered == null) {
            return ScanOutcome.Rejected("Enter the units before scanning the location")
        }
        if (!window.contains(input.bounds)) {
            return ScanOutcome.Rejected("The location is outside the scanning window")
        }
        val location = resolveLocation(input.raw)
            ?: return ScanOutcome.Rejected("'${input.raw}' is not a known location")

        if (location.value == Receiving) {
            return ScanOutcome.Rejected("Goods cannot be moved to the receiving location: locating them is a separate operation")
        }

        ScanOutcome.Advanced(
            ScanState.ReadyToConfirm(
                product = product,
                units = units ?: unitsEntered!!,
                to = location,
            )
        )
    }

    is ScanState.ReadyToConfirm -> ScanOutcome.Rejected(
        "There is already a movement ready to confirm. Confirm or cancel it first."
    )

    is ScanState.Failed -> ScanOutcome.Rejected(reason)
    }
}

/** Alias for the name of the receiving location, to avoid repeating literals. */
private const val Receiving = "RECEIVING"
