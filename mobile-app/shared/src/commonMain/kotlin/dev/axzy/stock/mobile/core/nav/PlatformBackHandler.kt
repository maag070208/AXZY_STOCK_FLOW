package dev.axzy.stock.mobile.core.nav

import androidx.compose.runtime.Composable

/**
 * Runs [onBack] when the system back action fires while [enabled]. Each platform owns its back action:
 * Android has a system back button; iOS navigates with the bar's back arrow.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
