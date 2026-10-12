package dev.axzy.stock.mobile.core.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.ITShape
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.core.theme.Brand
import dev.axzy.stock.mobile.core.ui.ITBrandLogo
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.app_name
import dev.axzy.stock.mobile.generated.resources.drawer_section_development
import dev.axzy.stock.mobile.generated.resources.drawer_section_modules
import dev.axzy.stock.mobile.generated.resources.drawer_sign_out
import dev.axzy.stock.mobile.generated.resources.nav_component_catalog
import dev.axzy.stock.mobile.generated.resources.nav_home
import dev.axzy.stock.mobile.generated.resources.nav_receiving
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The navigation drawer: the brand and who is signed in, the modules an operator opens, the development tools
 * apart, and the way out. [selected] is the section on display; choosing an entry calls [onSelect] with the
 * section it opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ITDrawer(selected: Screen, onSelect: (Screen) -> Unit, userName: String, onSignOut: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(drawerContainerColor = colors.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ITSpacing.M)
                .clip(ITShape.Card)
                .background(Brand.Hero, ITShape.Card)
                .padding(ITSpacing.L),
            verticalArrangement = Arrangement.spacedBy(ITSpacing.S),
        ) {
            Box(
                modifier = Modifier.size(width = 92.dp, height = 56.dp).clip(ITShape.Row).background(colors.onPrimary, ITShape.Row),
                contentAlignment = Alignment.Center,
            ) {
                ITBrandLogo(modifier = Modifier.width(72.dp))
            }
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onPrimary,
            )
            Text(
                text = userName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimary.copy(alpha = 0.8f),
            )
        }

        Spacer(Modifier.height(ITSpacing.M))
        Text(
            text = stringResource(Res.string.drawer_section_modules),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ITSpacing.XL, vertical = ITSpacing.S),
        )
        DrawerEntry(Screen.Home, Icons.Filled.Home, Res.string.nav_home, selected, onSelect)
        DrawerEntry(Screen.Invoices, Icons.Filled.Inventory2, Res.string.nav_receiving, selected, onSelect)

        Spacer(Modifier.height(ITSpacing.M))
        HorizontalDivider(modifier = Modifier.padding(horizontal = ITSpacing.L))
        Text(
            text = stringResource(Res.string.drawer_section_development),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ITSpacing.XL, vertical = ITSpacing.S),
        )
        DrawerEntry(
            Screen.ComponentCatalog,
            Icons.Filled.Widgets,
            Res.string.nav_component_catalog,
            selected,
            onSelect,
        )

        Spacer(Modifier.weight(1f))
        HorizontalDivider(modifier = Modifier.padding(horizontal = ITSpacing.L))
        NavigationDrawerItem(
            label = { Text(stringResource(Res.string.drawer_sign_out)) },
            selected = false,
            onClick = onSignOut,
            icon = { Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).padding(vertical = ITSpacing.S),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerEntry(
    screen: Screen,
    icon: ImageVector,
    label: StringResource,
    selected: Screen,
    onSelect: (Screen) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    NavigationDrawerItem(
        label = { Text(stringResource(label)) },
        selected = selected == screen,
        onClick = { onSelect(screen) },
        icon = { Icon(imageVector = icon, contentDescription = null) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = colors.primary.copy(alpha = 0.12f),
            selectedIconColor = colors.primary,
            selectedTextColor = colors.primary,
        ),
    )
}
