package dev.axzy.stock.mobile.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.AppShape
import dev.axzy.stock.mobile.core.theme.AppSpacing
import dev.axzy.stock.mobile.core.ui.AppCard
import dev.axzy.stock.mobile.core.ui.AppScaffold
import dev.axzy.stock.mobile.core.ui.BrandLogo
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.app_name
import dev.axzy.stock.mobile.generated.resources.home_component_catalog
import dev.axzy.stock.mobile.generated.resources.home_modules
import dev.axzy.stock.mobile.generated.resources.home_receiving_subtitle
import dev.axzy.stock.mobile.generated.resources.home_receiving_title
import dev.axzy.stock.mobile.generated.resources.home_subtitle
import org.jetbrains.compose.resources.stringResource

/** The start of the app: the modules an operator can open. */
@Composable
fun HomeScreen(
    onOpenReceiving: () -> Unit,
    onOpenComponentCatalog: () -> Unit,
) {
    AppScaffold(title = stringResource(Res.string.app_name)) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(AppSpacing.L),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.L),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.M),
            ) {
                BrandLogo(modifier = Modifier.size(64.dp))
                Text(
                    text = stringResource(Res.string.home_subtitle),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Text(
                text = stringResource(Res.string.home_modules),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModuleCard(
                icon = Icons.Filled.Inventory2,
                title = stringResource(Res.string.home_receiving_title),
                subtitle = stringResource(Res.string.home_receiving_subtitle),
                onClick = onOpenReceiving,
            )

            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenComponentCatalog, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.Widgets, contentDescription = null)
                Text(stringResource(Res.string.home_component_catalog), modifier = Modifier.padding(start = AppSpacing.S))
            }
        }
    }
}

@Composable
private fun ModuleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    AppCard(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.M),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(AppShape.Row)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), AppShape.Row),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
