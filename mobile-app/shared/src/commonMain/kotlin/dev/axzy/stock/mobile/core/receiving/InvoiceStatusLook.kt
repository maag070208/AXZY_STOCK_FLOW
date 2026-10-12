package dev.axzy.stock.mobile.core.receiving

import androidx.compose.ui.graphics.Color
import dev.axzy.stock.mobile.core.theme.Brand
import dev.axzy.stock.mobile.core.theme.ITColors
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.invoice_status_cancelled
import dev.axzy.stock.mobile.generated.resources.invoice_status_completed
import dev.axzy.stock.mobile.generated.resources.invoice_status_in_progress
import dev.axzy.stock.mobile.generated.resources.invoice_status_pending
import dev.axzy.stock.mobile.generated.resources.invoice_status_pending_to_locate
import org.jetbrains.compose.resources.StringResource

/** The name of an invoice status on screen. */
fun InvoiceStatus.labelRes(): StringResource = when (this) {
    InvoiceStatus.PENDING -> Res.string.invoice_status_pending
    InvoiceStatus.IN_PROGRESS -> Res.string.invoice_status_in_progress
    InvoiceStatus.PENDING_TO_LOCATE -> Res.string.invoice_status_pending_to_locate
    InvoiceStatus.COMPLETED -> Res.string.invoice_status_completed
    InvoiceStatus.CANCELLED -> Res.string.invoice_status_cancelled
}

/** The color of an invoice status: the same family the line statuses use for the same idea. */
fun InvoiceStatus.chipColor(): Color = when (this) {
    InvoiceStatus.PENDING -> ITColors.Warning
    InvoiceStatus.IN_PROGRESS -> Brand.Primary
    InvoiceStatus.PENDING_TO_LOCATE -> ITColors.Info
    InvoiceStatus.COMPLETED -> ITColors.Success
    InvoiceStatus.CANCELLED -> Brand.TextMuted
}
