package dev.axzy.stock.mobile.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import dev.axzy.stock.domain.identity.PasswordPolicy
import dev.axzy.stock.mobile.core.theme.ITColors
import dev.axzy.stock.mobile.core.theme.ITSpacing
import dev.axzy.stock.mobile.core.ui.ITAlert
import dev.axzy.stock.mobile.core.ui.ITButton
import dev.axzy.stock.mobile.core.ui.ITInput
import dev.axzy.stock.mobile.core.ui.ITPage
import dev.axzy.stock.mobile.core.ui.ITTextButton
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.common_sign_out
import dev.axzy.stock.mobile.generated.resources.password_confirm
import dev.axzy.stock.mobile.generated.resources.password_current
import dev.axzy.stock.mobile.generated.resources.password_hint
import dev.axzy.stock.mobile.generated.resources.password_new
import dev.axzy.stock.mobile.generated.resources.password_required_notice
import dev.axzy.stock.mobile.generated.resources.password_submit
import dev.axzy.stock.mobile.generated.resources.password_title
import org.jetbrains.compose.resources.stringResource

/**
 * The password change. It is shown in two ways. Opened from the drawer it has a back arrow. When the account
 * *must* change its password ([required]) there is nothing to go back to: it explains why, and the only other
 * way out is [onSignOut].
 */
@Composable
fun ChangePasswordScreen(
    viewModel: ChangePasswordViewModel,
    required: Boolean,
    onBack: (() -> Unit)?,
    onSignOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    ITPage(title = stringResource(Res.string.password_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(ITSpacing.L),
            verticalArrangement = Arrangement.spacedBy(ITSpacing.L),
        ) {
            if (required) {
                ITAlert(text = stringResource(Res.string.password_required_notice), color = ITColors.Info)
            }

            ITInput(
                value = state.current,
                onValueChange = viewModel::onCurrentChanged,
                label = stringResource(Res.string.password_current),
                isPassword = true,
                enabled = !state.isSubmitting,
                keyboardOptions = passwordKeyboard(ImeAction.Next),
            )
            ITInput(
                value = state.new,
                onValueChange = viewModel::onNewChanged,
                label = stringResource(Res.string.password_new),
                isPassword = true,
                enabled = !state.isSubmitting,
                keyboardOptions = passwordKeyboard(ImeAction.Next),
            )
            ITInput(
                value = state.confirm,
                onValueChange = viewModel::onConfirmChanged,
                label = stringResource(Res.string.password_confirm),
                isPassword = true,
                enabled = !state.isSubmitting,
                keyboardOptions = passwordKeyboard(ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
            )

            Text(
                text = stringResource(Res.string.password_hint, PasswordPolicy.MIN_LENGTH),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.error?.let { ITAlert(text = it.text(), color = ITColors.Danger) }

            ITButton(
                text = stringResource(Res.string.password_submit),
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                isLoading = state.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            )
            if (required) {
                ITTextButton(
                    text = stringResource(Res.string.common_sign_out),
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun passwordKeyboard(action: ImeAction) = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
    imeAction = action,
)
