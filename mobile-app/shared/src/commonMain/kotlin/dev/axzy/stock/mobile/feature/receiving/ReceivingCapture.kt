package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.Quantity

/** What the operator typed on the count screen, before it becomes domain values. */
data class CountInput(val units: Long, val boxes: Long)

/**
 * Captures the physical count of one invoice line.
 *
 * The app only turns what the operator typed into domain values and passes it on. Whether the line
 * balances, whether it is a discrepancy and whether it may be located are decided by `stock-domain`
 * (ADR-0003: the rules live in the domain, not in the feature).
 */
fun captureLine(line: InvoiceLine, input: CountInput, userId: Long): DomainResult<InvoiceLine> =
    line.capture(
        physicalQuantity = Quantity.of(input.units),
        physicalBoxQuantity = BoxQuantity.of(input.boxes),
        capturedBy = userId,
    )
