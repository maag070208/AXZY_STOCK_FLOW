package dev.axzy.stock.mobile.feature.auth

import androidx.compose.runtime.Composable
import dev.axzy.stock.mobile.core.network.ApiCodes
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.text
import dev.axzy.stock.mobile.core.session.SignInFailure
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.login_error_credentials
import dev.axzy.stock.mobile.generated.resources.login_error_disabled
import dev.axzy.stock.mobile.generated.resources.login_error_locked
import dev.axzy.stock.mobile.generated.resources.login_error_password_change
import org.jetbrains.compose.resources.stringResource

/**
 * The words for a sign-in that did not open a session, by the API's code. A wrong user and a wrong password read
 * the same on purpose: the API answers both with one code so that user names cannot be found out.
 */
@Composable
fun SignInFailure.text(): String = when (this) {
    SignInFailure.PasswordChangeRequired -> stringResource(Res.string.login_error_password_change)
    is SignInFailure.Api -> when ((failure as? ApiFailure.Rejected)?.code) {
        ApiCodes.INVALID_CREDENTIALS -> stringResource(Res.string.login_error_credentials)
        ApiCodes.ACCOUNT_LOCKED -> stringResource(Res.string.login_error_locked)
        ApiCodes.ACCOUNT_DISABLED -> stringResource(Res.string.login_error_disabled)
        else -> failure.text()
    }
}
