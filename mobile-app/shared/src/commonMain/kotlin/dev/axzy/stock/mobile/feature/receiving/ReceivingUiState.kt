package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus

/**
 * What the count screen shows. [line] is the domain's line: its status and numbers come from the domain after
 * each capture, never from a copy the screen keeps.
 */
data class ReceivingUiState(
    val line: InvoiceLine,
    /** The counts as the operator typed them. They become domain values only on submit. */
    val units: String = "",
    val boxes: String = "",
    /** Why the last submit was not accepted, if it was not. */
    val error: CountError? = null,
    /** The status the domain gave the line in the last accepted capture: it says whether the count matches the invoice. */
    val lastCapture: InvoiceLineStatus? = null,
)
