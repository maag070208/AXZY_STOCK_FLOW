package dev.axzy.stock.mobile.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.axzy.stock.mobile.core.theme.Brand
import dev.axzy.stock.mobile.core.theme.ITColors
import dev.axzy.stock.mobile.core.theme.ITShape
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.core.ui.ITAlert
import dev.axzy.stock.mobile.core.ui.ITBrandLogo
import dev.axzy.stock.mobile.core.ui.ITButton
import dev.axzy.stock.mobile.core.ui.ITCard
import dev.axzy.stock.mobile.core.ui.ITInput
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.app_name
import dev.axzy.stock.mobile.generated.resources.app_tagline
import dev.axzy.stock.mobile.generated.resources.login_password
import dev.axzy.stock.mobile.generated.resources.login_submit
import dev.axzy.stock.mobile.generated.resources.login_subtitle
import dev.axzy.stock.mobile.generated.resources.login_title
import dev.axzy.stock.mobile.generated.resources.login_username
import org.jetbrains.compose.resources.stringResource

/**
 * The door of the app: the brand over its gradient, and the card with the user, the password and the button.
 * Opening the session is all it does; where the app goes next follows from the session, not from this screen.
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colors = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxSize().background(Brand.Hero)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(ITSpacing.XL),
            verticalArrangement = Arrangement.spacedBy(ITSpacing.XL, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ITSpacing.S),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 148.dp, height = 88.dp)
                        .clip(ITShape.Card)
                        .background(colors.surface, ITShape.Card),
                    contentAlignment = Alignment.Center,
                ) {
                    ITBrandLogo(modifier = Modifier.width(116.dp))
                }
                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onPrimary,
                    modifier = Modifier.padding(top = ITSpacing.S),
                )
                Text(
                    text = stringResource(Res.string.app_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onPrimary.copy(alpha = 0.8f),
                )
            }

            ITCard(modifier = Modifier.widthIn(max = 420.dp), contentPadding = PaddingValues(ITSpacing.XL)) {
                Column(verticalArrangement = Arrangement.spacedBy(ITSpacing.L)) {
                    Column(verticalArrangement = Arrangement.spacedBy(ITSpacing.XS)) {
                        Text(
                            text = stringResource(Res.string.login_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onSurface,
                        )
                        Text(
                            text = stringResource(Res.string.login_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }

                    ITInput(
                        value = state.username,
                        onValueChange = viewModel::onUsernameChanged,
                        label = stringResource(Res.string.login_username),
                        enabled = !state.isSubmitting,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    ITInput(
                        value = state.password,
                        onValueChange = viewModel::onPasswordChanged,
                        label = stringResource(Res.string.login_password),
                        isPassword = true,
                        enabled = !state.isSubmitting,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                    )

                    state.error?.let { ITAlert(text = it.text(), color = ITColors.Danger) }

                    ITButton(
                        text = stringResource(Res.string.login_submit),
                        onClick = viewModel::submit,
                        enabled = state.canSubmit,
                        isLoading = state.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
