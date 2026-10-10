package dev.axzy.stock.mobile.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Corner radii shared by every component. Screens use these, never a literal radius. */
object AppShape {
    val Card = RoundedCornerShape(16.dp)
    val Row = RoundedCornerShape(12.dp)
    val Control = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(percent = 50)
}
