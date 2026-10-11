package dev.axzy.stock.mobile.core.nav

import androidx.compose.runtime.Composable

/** iOS has no system back button in this app: every screen goes back through the bar's arrow. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
