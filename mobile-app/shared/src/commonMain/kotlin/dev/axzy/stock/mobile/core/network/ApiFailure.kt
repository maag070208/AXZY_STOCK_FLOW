package dev.axzy.stock.mobile.core.network

import dev.axzy.stock.domain.common.Either
import kotlinx.serialization.Serializable

/**
 * Why a call to the API did not return what was asked. A screen decides by the case and by [Rejected.code],
 * never by a message: the API's messages are English and are rewritten, its codes are stable.
 */
sealed interface ApiFailure {

    /** The API answered with its error body. [details] depends on the code (`pendingLines`, `available`…). */
    data class Rejected(
        val status: Int,
        val code: String,
        val details: Map<String, String> = emptyMap(),
    ) : ApiFailure

    /** No answer arrived: there is no network, the address is wrong or the server is down. */
    data object Unreachable : ApiFailure

    /** An answer arrived that is not the contract's: a body that does not decode, a value the domain refuses. */
    data object Unexpected : ApiFailure
}

typealias ApiResult<T> = Either<ApiFailure, T>

/** The error body every route answers with (`docs/api/errores.md`). */
@Serializable
internal data class ApiErrorBody(
    val code: String,
    val message: String = "",
    val details: Map<String, String> = emptyMap(),
)

/** The codes the app tells apart. Any other code is shown as a generic failure. */
object ApiCodes {
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val ACCOUNT_DISABLED = "ACCOUNT_DISABLED"
    const val ACCOUNT_LOCKED = "ACCOUNT_LOCKED"
    const val WEAK_PASSWORD = "WEAK_PASSWORD"
    const val SAME_PASSWORD = "SAME_PASSWORD"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val INVALID_QUANTITY = "INVALID_QUANTITY"
    const val INCONSISTENT_BOX_COUNT = "INCONSISTENT_BOX_COUNT"
    const val INVALID_TRANSITION = "INVALID_TRANSITION"
    const val LINE_ALREADY_RESOLVED = "LINE_ALREADY_RESOLVED"
    const val LINE_CHANGED = "LINE_CHANGED"
}
