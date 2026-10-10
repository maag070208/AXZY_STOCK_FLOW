package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine

/**
 * What the count screen shows. [line] is the domain's line: its status and numbers come from the
 * domain after each capture, never from a copy the screen keeps.
 */
data class ReceivingUiState(
    val line: InvoiceLine,
    /** The counts as the operator typed them. They become domain values only on submit. */
    val units: String = "",
    val boxes: String = "",
    /** The input problem, or the domain's message for a rejected capture. */
    val error: String? = null,
)
