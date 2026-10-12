package dev.axzy.stock.mobile.core.receiving

import androidx.compose.ui.graphics.Color
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.mobile.core.theme.ITColors
import dev.axzy.stock.mobile.core.theme.Brand
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.status_approved
import dev.axzy.stock.mobile.generated.resources.status_assigned
import dev.axzy.stock.mobile.generated.resources.status_cancelled
import dev.axzy.stock.mobile.generated.resources.status_captured
import dev.axzy.stock.mobile.generated.resources.status_discrepancy
import dev.axzy.stock.mobile.generated.resources.status_located
import dev.axzy.stock.mobile.generated.resources.status_pending
import org.jetbrains.compose.resources.StringResource

/** The label of each status, translated. The domain's names never reach the screen. */
fun InvoiceLineStatus.labelRes(): StringResource = when (this) {
    InvoiceLineStatus.PENDING -> Res.string.status_pending
    InvoiceLineStatus.ASSIGNED -> Res.string.status_assigned
    InvoiceLineStatus.CAPTURED -> Res.string.status_captured
    InvoiceLineStatus.DISCREPANCY -> Res.string.status_discrepancy
    InvoiceLineStatus.LOCATED -> Res.string.status_located
    InvoiceLineStatus.APPROVED -> Res.string.status_approved
    InvoiceLineStatus.CANCELLED -> Res.string.status_cancelled
}

/** The color of each status. It only paints the state: what each status allows is decided by the domain. */
fun InvoiceLineStatus.chipColor(): Color = when (this) {
    InvoiceLineStatus.PENDING -> ITColors.Warning
    InvoiceLineStatus.ASSIGNED -> ITColors.Info
    InvoiceLineStatus.CAPTURED -> Brand.Primary
    InvoiceLineStatus.DISCREPANCY -> ITColors.Danger
    InvoiceLineStatus.LOCATED,
    InvoiceLineStatus.APPROVED -> ITColors.Success
    InvoiceLineStatus.CANCELLED -> Brand.TextMuted
}
