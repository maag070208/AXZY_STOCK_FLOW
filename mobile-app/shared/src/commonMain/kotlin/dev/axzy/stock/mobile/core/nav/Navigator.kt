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
    data object ReceivingList : Screen
    data class ReceivingCount(val lineId: Long) : Screen
    data object ComponentCatalog : Screen
}

/** A back stack: the last screen is the one on display. */
class Navigator(start: Screen) {
    private val stack = mutableStateListOf(start)

    val current: Screen get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun open(screen: Screen) {
        stack.add(screen)
    }

    fun back() {
        if (canGoBack) stack.removeAt(stack.lastIndex)
    }
}
