package dev.axzy.stock.mobile.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigatorTest {

    @Test
    fun `opening a screen shows it and back returns to the one before`() {
        val navigator = Navigator(Screen.Home)

        navigator.open(Screen.Invoices)
        navigator.open(Screen.ReceivingCount(lineId = 3))

        assertEquals(Screen.ReceivingCount(lineId = 3), navigator.current)
        navigator.back()
        assertEquals(Screen.Invoices, navigator.current)
    }

    @Test
    fun `back never leaves the first screen`() {
        val navigator = Navigator(Screen.Home)

        assertFalse(navigator.canGoBack)
        navigator.back()

        assertEquals(Screen.Home, navigator.current)
    }

    @Test
    fun `can go back only when a screen was opened on top of the first`() {
        val navigator = Navigator(Screen.Home)

        navigator.open(Screen.ComponentCatalog)

        assertTrue(navigator.canGoBack)
    }

    @Test
    fun `an invoice's lines and a count belong to the receiving section for the drawer and the tab bar`() {
        val navigator = Navigator(Screen.Home)

        navigator.open(Screen.Invoices)
        navigator.open(Screen.InvoiceLines(invoiceId = 15))
        assertEquals(Screen.Invoices, navigator.currentRoot)

        navigator.open(Screen.ReceivingCount(lineId = 1))
        assertEquals(Screen.Invoices, navigator.currentRoot)
    }

    @Test
    fun `the stack lists every screen that can still be gone back to the current one last`() {
        val navigator = Navigator(Screen.Invoices)
        navigator.open(Screen.InvoiceLines(invoiceId = 15))
        navigator.open(Screen.ReceivingCount(lineId = 1))
        assertEquals(
            listOf(Screen.Invoices, Screen.InvoiceLines(15), Screen.ReceivingCount(1)),
            navigator.screens.toList(),
        )

        navigator.back()

        assertEquals(listOf(Screen.Invoices, Screen.InvoiceLines(15)), navigator.screens.toList())
    }

    @Test
    fun `opening a section from the drawer starts the stack again from it`() {
        val navigator = Navigator(Screen.Home)
        navigator.open(Screen.Invoices)
        navigator.open(Screen.ReceivingCount(lineId = 1))

        navigator.openRoot(Screen.ComponentCatalog)

        assertEquals(Screen.ComponentCatalog, navigator.current)
        assertFalse(navigator.canGoBack)
    }
}
