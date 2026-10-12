package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine

/** The two numbers of a count. The number pad writes into the one that is active. */
enum class CountField { UNITS, BOXES }

/**
 * What the count screen shows. [line] is the domain's line: after a capture it is the one the API answered with,
 * never a copy the screen worked out.
 */
data class ReceivingUiState(
    val line: InvoiceLine,
    /** The product's name, when the API gave one. */
    val productName: String? = null,
    /** The counts as the operator typed them. They become domain values only on submit. */
    val units: String = "",
    val boxes: String = "",
    /** The field the number pad is writing to. */
    val activeField: CountField = CountField.UNITS,
    /** True while the count is on its way to the API. */
    val isSubmitting: Boolean = false,
    /** Why the last submit was not accepted, if it was not. */
    val error: CountError? = null,
)
