package dev.axzy.stock.mobile.core.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * Gives every screen on the back stack its own ViewModels. A screen keeps its state while it can still be gone
 * back to (a filter survives opening a count and returning), and loses it when it leaves the stack, so a screen
 * opened again starts from what its source says now and never from a copy it kept. Everything is cleared when
 * this leaves the composition: that is the end of the session.
 */
@Composable
fun ScreenViewModels(navigator: Navigator, content: @Composable () -> Unit) {
    val stores = remember(navigator) { mutableMapOf<Screen, ViewModelStore>() }
    val current = navigator.current
    val owner = remember(navigator, current) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = stores.getOrPut(current) { ViewModelStore() }
        }
    }

    val onStack = navigator.screens.toSet()
    SideEffect {
        (stores.keys - onStack).forEach { gone -> stores.remove(gone)?.clear() }
    }
    DisposableEffect(navigator) {
        onDispose {
            stores.values.forEach(ViewModelStore::clear)
            stores.clear()
        }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
