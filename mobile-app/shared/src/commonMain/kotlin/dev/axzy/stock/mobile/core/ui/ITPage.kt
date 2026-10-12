package dev.axzy.stock.mobile.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.common_back
import dev.axzy.stock.mobile.generated.resources.common_menu
import org.jetbrains.compose.resources.stringResource

/**
 * The frame of every screen: a light top bar, a navigation action (the drawer menu on a top-level screen, a back
 * arrow on a screen opened from another), the tab bar of the top-level screens, and the padding that keeps the
 * content clear of the status bar and the home indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ITPage(
    title: String,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    when {
                        onBack != null -> IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.common_back),
                            )
                        }
                        onMenu != null -> IconButton(onClick = onMenu) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = stringResource(Res.string.common_menu),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        bottomBar = bottomBar,
    ) { padding -> content(padding) }
}
