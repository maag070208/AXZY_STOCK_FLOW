package dev.axzy.stock.mobile.core.nav

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.nav_catalog_short
import dev.axzy.stock.mobile.generated.resources.nav_home
import dev.axzy.stock.mobile.generated.resources.nav_receiving
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The tab bar of the top-level screens: the three places an operator goes most. [selected] is the section on
 * display; tapping a tab opens that section from the start, as the drawer does.
 */
@Composable
fun ITTabBar(selected: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        Tab(Screen.Home, Icons.Filled.Home, Res.string.nav_home, selected, onSelect)
        Tab(Screen.Invoices, Icons.Filled.Inventory2, Res.string.nav_receiving, selected, onSelect)
        Tab(Screen.ComponentCatalog, Icons.Filled.Widgets, Res.string.nav_catalog_short, selected, onSelect)
    }
}

@Composable
private fun RowScope.Tab(
    screen: Screen,
    icon: ImageVector,
    label: StringResource,
    selected: Screen,
    onSelect: (Screen) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    NavigationBarItem(
        selected = selected == screen,
        onClick = { onSelect(screen) },
        icon = { Icon(imageVector = icon, contentDescription = null) },
        label = { Text(stringResource(label)) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = colors.primary,
            selectedTextColor = colors.primary,
            indicatorColor = colors.primary.copy(alpha = 0.12f),
            unselectedIconColor = colors.onSurfaceVariant,
            unselectedTextColor = colors.onSurfaceVariant,
        ),
    )
}
