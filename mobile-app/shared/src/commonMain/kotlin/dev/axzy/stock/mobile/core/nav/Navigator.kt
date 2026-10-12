package dev.axzy.stock.mobile.core.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/**
 * Every screen the app can show. A screen that works on data carries only the id; its route reads
 * the rest from the source that owns it, so a screen never shows a copy that went stale.
 */
sealed interface Screen {
    data object Home : Screen

    /** The invoices that still take counts. */
    data object Invoices : Screen

    /** The lines of one invoice. */
    data class InvoiceLines(val invoiceId: Long) : Screen

    /** The count of one line of the invoice that is open. */
    data class ReceivingCount(val lineId: Long) : Screen

    data object ComponentCatalog : Screen
}

/** A back stack: the last screen is the one on display. */
class Navigator(start: Screen) {
    private val stack = mutableStateListOf(start)

    val current: Screen get() = stack.last()

    /** Every screen that can still be gone back to, the current one last. */
    val screens: List<Screen> get() = stack

    /** The section the current screen belongs to: an invoice's lines and a count belong to receiving. */
    val currentRoot: Screen
        get() = when (val screen = current) {
            is Screen.InvoiceLines, is Screen.ReceivingCount -> Screen.Invoices
            else -> screen
        }

    val canGoBack: Boolean get() = stack.size > 1

    fun open(screen: Screen) {
        stack.add(screen)
    }

    /** Jumps to a section from the drawer: the stack starts again from that section. */
    fun openRoot(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    fun back() {
        if (canGoBack) stack.removeAt(stack.lastIndex)
    }
}
