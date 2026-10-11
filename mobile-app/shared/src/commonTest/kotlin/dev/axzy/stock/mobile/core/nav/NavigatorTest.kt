package dev.axzy.stock.mobile.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigatorTest {

    @Test
    fun `opening a screen shows it and back returns to the one before`() {
        val navigator = Navigator(Screen.Home)

        navigator.open(Screen.ReceivingList)
        navigator.open(Screen.ReceivingCount(lineId = 3))

        assertEquals(Screen.ReceivingCount(lineId = 3), navigator.current)
        navigator.back()
        assertEquals(Screen.ReceivingList, navigator.current)
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
}
