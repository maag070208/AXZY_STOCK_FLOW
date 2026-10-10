package dev.axzy.stock.mobile.core.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand tokens, copied from the web theme (`web/src/app/main.tsx`) and the visual identity
 * (`docs/guia/identidad-visual.md`). Change them here, never in a screen.
 */
object Brand {
    val Primary = Color(0xFF16315F)
    val Secondary = Color(0xFF3A86A8)
    val Background = Color(0xFFF2F6FC)
    val Surface = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF1E293B)
    val TextMuted = Color(0xFF475569)
}

/** Status colors. They mean a state, not the brand. */
object AppColors {
    val Alert = Color(0xFFF28C28)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFDC2626)
    val Success = Color(0xFF16A34A)
    val Info = Color(0xFF3A86A8)
}
