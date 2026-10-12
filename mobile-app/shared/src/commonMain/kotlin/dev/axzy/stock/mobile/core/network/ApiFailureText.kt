package dev.axzy.stock.mobile.core.network

import androidx.compose.runtime.Composable
import dev.axzy.stock.mobile.generated.resources.Res
import dev.axzy.stock.mobile.generated.resources.common_error_permission
import dev.axzy.stock.mobile.generated.resources.common_error_unknown
import dev.axzy.stock.mobile.generated.resources.common_error_unreachable
import org.jetbrains.compose.resources.stringResource

/**
 * The words for a failure that no screen has a better sentence for. A screen that knows the case (a wrong
 * password, a line someone else counted) maps its own codes first and falls back to this.
 */
@Composable
fun ApiFailure.text(): String = when (this) {
    ApiFailure.Unreachable -> stringResource(Res.string.common_error_unreachable)
    is ApiFailure.Rejected ->
        if (code == ApiCodes.PERMISSION_DENIED) {
            stringResource(Res.string.common_error_permission)
        } else {
            stringResource(Res.string.common_error_unknown)
        }
    ApiFailure.Unexpected -> stringResource(Res.string.common_error_unknown)
}
