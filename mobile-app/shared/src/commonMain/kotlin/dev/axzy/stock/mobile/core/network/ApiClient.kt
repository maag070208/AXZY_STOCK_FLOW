package dev.axzy.stock.mobile.core.network

import dev.axzy.stock.domain.common.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The channel the audit log records for every request of the app (`docs/api/convenciones.md` §1.6). */
private const val CLIENT_CHANNEL = "MOBILE"

/**
 * The one door to the API. It adds the session's token and the channel header, turns every outcome into an
 * [ApiResult] (it never throws for a refused or failed call), and renews the session once when the API answers
 * that the token is no longer valid.
 *
 * It knows nothing about the session: [accessToken] and [renewSession] are set by whoever owns it.
 * [engine] is only passed by tests; without it, Ktor picks the platform's engine.
 */
class ApiClient(
    private val baseUrl: String,
    engine: HttpClientEngine? = null,
) {
    /** The token to send, if there is a session. */
    var accessToken: () -> String? = { null }

    /** Renews the session after the API refused [refusedToken]. True when a new token is ready to retry with. */
    var renewSession: suspend (refusedToken: String) -> Boolean = { false }

    @PublishedApi
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val http: HttpClient =
        if (engine != null) HttpClient(engine) { expectSuccess = false } else HttpClient { expectSuccess = false }

    suspend inline fun <reified T> get(path: String): ApiResult<T> =
        send(HttpMethod.Get, path, body = null).decoded()

    suspend inline fun <reified B, reified T> post(path: String, body: B, authenticated: Boolean = true): ApiResult<T> =
        send(HttpMethod.Post, path, json.encodeToString(body), authenticated).decoded()

    /** A POST whose answer has no body (`204`). */
    suspend inline fun <reified B> postNoContent(path: String, body: B, authenticated: Boolean = true): ApiResult<Unit> =
        when (val sent = send(HttpMethod.Post, path, json.encodeToString(body), authenticated)) {
            is Either.Left -> sent
            is Either.Right -> Either.Right(Unit)
        }

    /** Sends the request and returns the body of a successful answer as text. */
    @PublishedApi
    internal suspend fun send(method: HttpMethod, path: String, body: String?, authenticated: Boolean = true): ApiResult<String> {
        val token = if (authenticated) accessToken() else null
        val first = execute(method, path, body, token)
        // One retry, and only for the code that means "this token is no longer valid": a wrong password is also a 401.
        if (token != null && first.isUnauthenticated() && renewSession(token)) {
            return execute(method, path, body, accessToken())
        }
        return first
    }

    @PublishedApi
    internal inline fun <reified T> ApiResult<String>.decoded(): ApiResult<T> = when (this) {
        is Either.Left -> this
        is Either.Right -> try {
            Either.Right(json.decodeFromString<T>(value))
        } catch (_: IllegalArgumentException) {
            Either.Left(ApiFailure.Unexpected)
        }
    }

    private suspend fun execute(method: HttpMethod, path: String, body: String?, token: String?): ApiResult<String> =
        try {
            val response = http.request(baseUrl + path) {
                this.method = method
                header(HEADER_CLIENT_CHANNEL, CLIENT_CHANNEL)
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val text = response.bodyAsText()
            if (response.status.isSuccess()) Either.Right(text) else Either.Left(rejection(response.status, text))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing but the exchange itself runs here, so whatever it throws means the answer never arrived.
            Either.Left(ApiFailure.Unreachable)
        }

    private fun rejection(status: HttpStatusCode, text: String): ApiFailure = try {
        val body = json.decodeFromString<ApiErrorBody>(text)
        ApiFailure.Rejected(status = status.value, code = body.code, details = body.details)
    } catch (_: IllegalArgumentException) {
        ApiFailure.Unexpected
    }

    private fun ApiResult<String>.isUnauthenticated(): Boolean =
        this is Either.Left && (error as? ApiFailure.Rejected)?.code == ApiCodes.UNAUTHENTICATED

    private companion object {
        const val HEADER_CLIENT_CHANNEL = "X-Client-Channel"
    }
}
