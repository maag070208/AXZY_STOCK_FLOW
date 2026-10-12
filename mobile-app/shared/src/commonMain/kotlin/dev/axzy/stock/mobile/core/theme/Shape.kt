package dev.axzy.stock.mobile.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Corner radii shared by every component, taken from the web's design system (`@axzydev/axzy_ui_system`):
 * its cards, alerts and stat cards use `radius-xl`, its buttons and inputs `radius-lg`, its small pieces
 * `radius-md`, and its chips are fully round. Screens use these, never a literal radius.
 */
object ITShape {
    /** `radius-xl`, 1.125rem. */
    val Card = RoundedCornerShape(18.dp)

    /** `radius-lg`, 0.875rem. */
    val Control = RoundedCornerShape(14.dp)

    /** `radius-md`, 0.625rem. */
    val Row = RoundedCornerShape(10.dp)

    val Pill = RoundedCornerShape(percent = 50)
}
