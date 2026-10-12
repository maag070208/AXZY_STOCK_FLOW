package dev.axzy.stock.mobile.core.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Brand tokens, copied from the web theme (`web/src/app/main.tsx`) and the visual identity
 * (`docs/guia/identidad-visual.md`). The neutrals are the slate scale the web's design system uses for its
 * borders and muted text. Change them here, never in a screen.
 */
object Brand {
    val Primary = Color(0xFF16315F)
    val Secondary = Color(0xFF3A86A8)
    val Background = Color(0xFFF2F6FC)
    val Surface = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF1E293B)
    val TextMuted = Color(0xFF475569)

    /** The thin border of cards and fields: slate-200. */
    val Border = Color(0xFFE2E8F0)

    /** A border that must be seen, such as the outline of an input: slate-300. */
    val BorderStrong = Color(0xFFCAD5E2)

    /** The hero of the home screen and the drawer: the navy of the logo running into the turquoise. */
    val Hero = Brush.linearGradient(listOf(Primary, Secondary))
}

/** Status colors. They mean a state, not the brand. */
object ITColors {
    val Alert = Color(0xFFF28C28)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFDC2626)
    val Success = Color(0xFF16A34A)
    val Info = Color(0xFF3A86A8)
}
