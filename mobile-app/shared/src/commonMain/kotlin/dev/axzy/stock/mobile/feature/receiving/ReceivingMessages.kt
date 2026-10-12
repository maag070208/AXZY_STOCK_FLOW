package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.runtime.Composable
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.mobile.core.network.ApiCodes
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.text
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.receiving_capture_matches
import dev.axzy.stock.mobile.generated.resources.receiving_difference_over
import dev.axzy.stock.mobile.generated.resources.receiving_difference_short
import dev.axzy.stock.mobile.generated.resources.receiving_error_inconsistent_generic
import dev.axzy.stock.mobile.generated.resources.receiving_error_input
import dev.axzy.stock.mobile.generated.resources.receiving_error_inconsistent_boxes
import dev.axzy.stock.mobile.generated.resources.receiving_error_invalid_quantity
import dev.axzy.stock.mobile.generated.resources.receiving_error_invalid_transition
import dev.axzy.stock.mobile.generated.resources.receiving_error_line_changed
import dev.axzy.stock.mobile.generated.resources.receiving_error_line_resolved
import dev.axzy.stock.mobile.generated.resources.receiving_error_unknown
import kotlin.math.abs
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The words for a count that was not accepted. The domain's and the API's own messages are English and
 * technical; the app translates by the case and by the code, so every rejection reads in the operator's language.
 */
@Composable
fun CountError.text(): String = when (this) {
    CountError.InvalidInput -> stringResource(Res.string.receiving_error_input)
    is CountError.Rejected -> when (val domainError = error) {
        is DomainError.InvalidQuantity -> stringResource(Res.string.receiving_error_invalid_quantity)
        is DomainError.InconsistentBoxCount -> stringResource(
            Res.string.receiving_error_inconsistent_boxes,
            domainError.quantity,
            domainError.boxes,
            domainError.unitsPerBox,
        )
        is DomainError.InvalidTransition -> stringResource(Res.string.receiving_error_invalid_transition)
        else -> stringResource(Res.string.receiving_error_unknown)
    }
    is CountError.Failed -> when ((failure as? ApiFailure.Rejected)?.code) {
        ApiCodes.INVALID_QUANTITY -> stringResource(Res.string.receiving_error_invalid_quantity)
        ApiCodes.INCONSISTENT_BOX_COUNT -> stringResource(Res.string.receiving_error_inconsistent_generic)
        ApiCodes.INVALID_TRANSITION -> stringResource(Res.string.receiving_error_invalid_transition)
        ApiCodes.LINE_ALREADY_RESOLVED -> stringResource(Res.string.receiving_error_line_resolved)
        ApiCodes.LINE_CHANGED -> stringResource(Res.string.receiving_error_line_changed)
        else -> failure.text()
    }
}

/**
 * What a counted line says about the invoice, in the domain's own figure: its `difference` is what was counted
 * minus what was invoiced. It reads "matches", or how many units are missing or over. Null while the line has
 * no count.
 */
@Composable
fun InvoiceLine.differenceText(): String? {
    val difference = difference ?: return null
    val units = abs(difference.units)
    return when {
        difference.isZero -> stringResource(Res.string.receiving_capture_matches)
        difference.isNegative -> pluralStringResource(Res.plurals.receiving_difference_short, units.toInt(), units)
        else -> pluralStringResource(Res.plurals.receiving_difference_over, units.toInt(), units)
    }
}
