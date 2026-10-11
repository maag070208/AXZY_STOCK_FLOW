package dev.axzy.stock.mobile.feature.receiving

import androidx.compose.runtime.Composable
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.receiving_capture_discrepancy
import dev.axzy.stock.mobile.generated.resources.receiving_capture_matches
import dev.axzy.stock.mobile.generated.resources.receiving_error_input
import dev.axzy.stock.mobile.generated.resources.receiving_error_inconsistent_boxes
import dev.axzy.stock.mobile.generated.resources.receiving_error_invalid_quantity
import dev.axzy.stock.mobile.generated.resources.receiving_error_invalid_transition
import dev.axzy.stock.mobile.generated.resources.receiving_error_unknown
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The words for a count that was not accepted. The domain's own message is English and technical; the app
 * translates by the case, so every rejection reads in the operator's language.
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
}

/**
 * What an accepted capture says about the invoice: a match, or a discrepancy that was recorded. Any other
 * status says nothing, so the screen shows no message for it.
 */
fun InvoiceLineStatus.captureResultRes(): StringResource? = when (this) {
    InvoiceLineStatus.CAPTURED -> Res.string.receiving_capture_matches
    InvoiceLineStatus.DISCREPANCY -> Res.string.receiving_capture_discrepancy
    else -> null
}
