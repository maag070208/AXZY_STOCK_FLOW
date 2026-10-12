package dev.axzy.stock.mobile.feature.auth

import androidx.compose.runtime.Composable
import dev.axzy.stock.domain.identity.PasswordPolicy
import dev.axzy.stock.domain.identity.PasswordRejection
import dev.axzy.stock.mobile.core.network.ApiCodes
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.text
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.password_error_blank
import dev.axzy.stock.mobile.generated.resources.password_error_common
import dev.axzy.stock.mobile.generated.resources.password_error_contains_username
import dev.axzy.stock.mobile.generated.resources.password_error_current
import dev.axzy.stock.mobile.generated.resources.password_error_mismatch
import dev.axzy.stock.mobile.generated.resources.password_error_same
import dev.axzy.stock.mobile.generated.resources.password_error_too_long
import dev.axzy.stock.mobile.generated.resources.password_error_too_short
import dev.axzy.stock.mobile.generated.resources.password_error_too_simple
import dev.axzy.stock.mobile.generated.resources.password_error_weak
import org.jetbrains.compose.resources.stringResource

/** The words for a password change that was not made, by the domain's rejection or the API's code. */
@Composable
fun PasswordError.text(): String = when (this) {
    PasswordError.Mismatch -> stringResource(Res.string.password_error_mismatch)
    is PasswordError.Weak -> when (rejection) {
        PasswordRejection.TOO_SHORT -> stringResource(Res.string.password_error_too_short, PasswordPolicy.MIN_LENGTH)
        PasswordRejection.TOO_LONG -> stringResource(Res.string.password_error_too_long, PasswordPolicy.MAX_LENGTH)
        PasswordRejection.ONLY_WHITESPACE -> stringResource(Res.string.password_error_blank)
        PasswordRejection.COMMON -> stringResource(Res.string.password_error_common)
        PasswordRejection.CONTAINS_USERNAME -> stringResource(Res.string.password_error_contains_username)
        PasswordRejection.TOO_SIMPLE -> stringResource(Res.string.password_error_too_simple)
    }
    is PasswordError.Failed -> when ((failure as? ApiFailure.Rejected)?.code) {
        ApiCodes.INVALID_CREDENTIALS -> stringResource(Res.string.password_error_current)
        ApiCodes.SAME_PASSWORD -> stringResource(Res.string.password_error_same)
        ApiCodes.WEAK_PASSWORD -> stringResource(Res.string.password_error_weak)
        else -> failure.text()
    }
}
