package dev.axzy.stock.network.client

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.network.dto.auth.AuthSessionDto
import dev.axzy.stock.network.dto.auth.RefreshTokenRequest
import dev.axzy.stock.network.dto.common.ErrorDto
import dev.axzy.stock.network.error.ApiError
import dev.axzy.stock.network.error.ApiOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL CLIENTE HTTP
 *
 * Responsabilidades, y ninguna más:
 *
 *  1. **Cabeceras del contrato** (§1.6): identidad, correlación, canal y zona.
 *  2. **`Idempotency-Key`** en las operaciones que mueven inventario (§1.5).
 *  3. **Refresh automático ante `401`, reintentando UNA sola vez** (§2).
 *  4. **Traducir el `ErrorDto`** a [ApiError], decidiendo **por `code`** (§1.3).
 *
 * Lo que NO hace: reglas de negocio. Aquí no se decide si una diferencia se
 * aprueba ni si un movimiento resta del total; eso vive en `stock-domain` y este
 * módulo solo lo transporta.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class StockHttpClient(
    val config: StockApiConfig,
    private val tokens: TokenStorePort = InMemoryTokenStore(),
    engine: HttpClientEngine? = null,
    private val requestIds: RequestIdGenerator = RandomRequestIdGenerator,
) {

    /**
     * Se invoca **cuando la sesión ya no es recuperable**: el refresh token fue
     * rechazado o se detectó su reuso. La app debe limpiar su estado en memoria y
     * volver al login.
     *
     * No se invoca por un fallo de red durante el refresh: ahí la sesión puede
     * seguir siendo válida, y cerrar sesión por un corte de cobertura dejaría al
     * operador fuera de la app en mitad del almacén.
     */
    var onSessionExpired: () -> Unit = {}

    /** Serializa los refresh: dos peticiones que reciben `401` a la vez no rotan dos veces. */
    private val refreshMutex = Mutex()

    private val http: HttpClient = HttpClient(engine ?: platformHttpEngine()) {
        // Los errores HTTP NO son excepciones: son resultados que se traducen a
        // `ApiError`. Dejar que Ktor lance obligaría a un `try/catch` por llamada,
        // que es exactamente el patrón que el legacy usaba para silenciar errores.
        expectSuccess = false

        install(ContentNegotiation) { json(stockJson) }

        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
        }

        if (config.logRequests) {
            install(Logging) {
                level = LogLevel.INFO
                // Un log de peticiones con el token dentro es un log con sesiones
                // válidas: se enmascara la cabecera de identidad.
                sanitizeHeader { name -> name.equals(HttpHeaders.Authorization, ignoreCase = true) }
            }
        }

        defaultRequest {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
    }

    fun close() {
        http.close()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API INTERNA que consumen los *Api de cada recurso
    // ═════════════════════════════════════════════════════════════════════════

    internal suspend fun getText(
        path: String,
        query: List<Pair<String, String?>> = emptyList(),
        authenticated: Boolean = true,
    ): ApiOutcome<HttpResponseBody> = send(HttpMethod.Get, path, query, authenticated = authenticated)

    /**
     * `GET` que devuelve el cuerpo **aunque el estado no sea 2xx**.
     *
     * Existe por `GET /v1/health`: con `status = DOWN` el contrato responde
     * `503` **con el cuerpo del estado dentro**. Tratar ese `503` como un fallo
     * de transporte perdería el diagnóstico (`database.error`, `schemaVersion`)
     * que es justo lo que se ha ido a buscar.
     */
    internal suspend fun getAllowAnyStatus(
        path: String,
        authenticated: Boolean = false,
    ): ApiOutcome<HttpResponseBody> {
        val attempt = attempt(HttpMethod.Get, path, emptyList(), null, null, null, authenticated)
        return attempt.body?.asRight()
            ?: (attempt.error ?: ApiError.MalformedResponse()).asLeft()
    }

    internal suspend fun postText(
        path: String,
        body: String? = null,
        query: List<Pair<String, String?>> = emptyList(),
        idempotencyKey: String? = null,
        authenticated: Boolean = true,
    ): ApiOutcome<HttpResponseBody> = send(
        HttpMethod.Post, path, query, body, null, idempotencyKey, authenticated,
    )

    /**
     * `POST` con un cuerpo que no es texto JSON.
     *
     * Existe por el import del catálogo, que es `multipart/form-data`. Va por el
     * mismo camino que el resto (`send`), así que hereda las cabeceras, el
     * refresh ante `401` y la traducción de errores: un endpoint de subida que se
     * saltara ese camino tendría su propia política de errores, y con ella su
     * propio conjunto de fallos silenciosos.
     */
    internal suspend fun postMultipart(
        path: String,
        bodyBlock: HttpRequestBuilder.() -> Unit,
    ): ApiOutcome<HttpResponseBody> = send(
        HttpMethod.Post, path, bodyBlock = bodyBlock,
    )

    internal suspend fun putText(
        path: String,
        body: String? = null,
        authenticated: Boolean = true,
    ): ApiOutcome<HttpResponseBody> = send(HttpMethod.Put, path, body = body, authenticated = authenticated)

    internal suspend fun patchText(
        path: String,
        body: String? = null,
        authenticated: Boolean = true,
    ): ApiOutcome<HttpResponseBody> = send(HttpMethod.Patch, path, body = body, authenticated = authenticated)

    /**
     * `DELETE` con cuerpo. El contrato exige `reason` en el cuerpo del borrado
     * lógico de un producto, así que no vale un `delete` sin cuerpo.
     */
    internal suspend fun deleteText(
        path: String,
        body: String? = null,
        authenticated: Boolean = true,
    ): ApiOutcome<HttpResponseBody> = send(HttpMethod.Delete, path, body = body, authenticated = authenticated)

    /**
     * Igual que [postText] pero **sin cuerpo de respuesta** (`204`).
     *
     * Un `204` no se puede deserializar: intentar decodificarlo como un DTO
     * produciría un error de formato en una operación que en realidad funcionó.
     */
    internal suspend fun postNoContent(
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
        authenticated: Boolean = true,
    ): ApiOutcome<Unit> = send(
        HttpMethod.Post, path, body = body, idempotencyKey = idempotencyKey, authenticated = authenticated,
    ).mapUnit()

    internal suspend fun deleteNoContent(
        path: String,
        body: String? = null,
        authenticated: Boolean = true,
    ): ApiOutcome<Unit> = send(
        HttpMethod.Delete, path, body = body, authenticated = authenticated,
    ).mapUnit()

    /** Descarga binaria (exportaciones de reportes, `Content-Disposition: attachment`). */
    internal suspend fun download(
        path: String,
        query: List<Pair<String, String?>> = emptyList(),
    ): ApiOutcome<DownloadedFile> {
        val session = tokens.load()
        val requestId = requestIds.next()
        val response = execute(session?.accessToken, requestId, HttpMethod.Get, path, query, null, null, null)
            ?: return ApiError.Network().asLeft()

        if (!response.status.isSuccess()) {
            val text = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            val responseId = response.headers[StockHeaders.REQUEST_ID] ?: requestId
            return parseError(text, response.status.value, responseId).asLeft()
        }

        return try {
            DownloadedFile(
                bytes = response.readRawBytes(),
                fileName = fileNameOf(response.headers[HttpHeaders.ContentDisposition]),
                contentType = response.headers[HttpHeaders.ContentType],
            ).asRight()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            ApiError.MalformedResponse(
                message = "No se pudo leer el archivo descargado.",
                httpStatus = response.status.value,
                requestId = requestId,
                cause = t,
            ).asLeft()
        }
    }

    /** Guarda la sesión que devolvió el login o el refresh. */
    internal suspend fun storeSession(session: AuthSessionDto) {
        tokens.save(
            StoredSession(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                // Se guarda el INSTANTE, no el `expiresIn`: después de reiniciar
                // la app, un `expiresIn` no dice cuándo se emitió el token.
                expiresAtEpochSeconds = config.clock.nowEpochSeconds() + session.expiresIn,
            )
        )
    }

    /**
     * Descarta la sesión local.
     *
     * Se usa al cerrar sesión y cuando el servidor invalida las sesiones (cambio
     * de contraseña, reuso de token). Limpiar es **local e incondicional**: aunque
     * el servidor falle, los tokens de este dispositivo dejan de existir.
     */
    internal suspend fun clearSession() {
        tokens.clear()
    }

    /** La sesión guardada, para lo que necesite el refresh token (cierre de sesión). */
    internal suspend fun currentSession(): StoredSession? = tokens.load()

    // ═════════════════════════════════════════════════════════════════════════
    // DECODIFICACIÓN (inline + reified: el tipo lo pone quien llama)
    // ═════════════════════════════════════════════════════════════════════════

    private fun ApiOutcome<HttpResponseBody>.mapUnit(): ApiOutcome<Unit> = when (this) {
        is Either.Left -> this
        is Either.Right -> Unit.asRight()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NÚCLEO: cabeceras, refresh de una sola pasada y traducción de errores
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun send(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String?>> = emptyList(),
        body: String? = null,
        bodyBlock: (HttpRequestBuilder.() -> Unit)? = null,
        idempotencyKey: String? = null,
        authenticated: Boolean = true,
    ): ApiOutcome<HttpResponseBody> {
        val first = attempt(method, path, query, body, bodyBlock, idempotencyKey, authenticated)
        // Se mira el ERROR primero, no el cuerpo: desde que el cuerpo se conserva
        // también en las respuestas de error (lo necesita `GET /v1/health`), un
        // `401` traería cuerpo y se colaría como si fuera un éxito.
        if (first.error == null) return first.asOutcome()
        val error = first.error

        // Solo se intenta refrescar si el fallo es de identidad y la petición la
        // llevaba. Un 403 no se arregla refrescando.
        if (!authenticated || error !is ApiError.Unauthenticated) return error.asLeft()

        return when (val outcome = refreshSession(first.usedAccessToken)) {
            // El reintento sale por un camino que NO puede refrescar: así el
            // bucle es estructuralmente imposible, no «improbable».
            is RefreshOutcome.Renewed, RefreshOutcome.AlreadyRenewed -> {
                val retry = attempt(method, path, query, body, bodyBlock, idempotencyKey, authenticated = true)
                retry.asOutcome()
            }

            is RefreshOutcome.Failed -> when (val refreshError = outcome.error) {
                // Un fallo reintentable del refresh no cierra la sesión: la
                // cobertura vuelve y el operador sigue dentro.
                null -> error.asLeft()
                is ApiError.Network, is ApiError.ServerUnavailable, is ApiError.RateLimited ->
                    error.asLeft()

                // `TOKEN_REUSED`, `ACCOUNT_LOCKED`… son accionables y explican
                // mejor lo que pasó que el 401 original.
                else -> refreshError.asLeft()
            }
        }
    }

    /** Una petición, sin refrescar nada. Devuelve el cuerpo, el error o ninguno de los dos. */
    private suspend fun attempt(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String?>>,
        body: String?,
        bodyBlock: (HttpRequestBuilder.() -> Unit)?,
        idempotencyKey: String?,
        authenticated: Boolean,
    ): Attempt {
        val session = if (authenticated) tokens.load() else null
        val requestId = requestIds.next()
        val response = execute(session?.accessToken, requestId, method, path, query, body, bodyBlock, idempotencyKey)
            ?: return Attempt(error = ApiError.Network(requestId = requestId), usedAccessToken = session?.accessToken)

        val responseId = response.headers[StockHeaders.REQUEST_ID] ?: requestId
        val text = try {
            response.bodyAsText()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            return Attempt(
                error = ApiError.Network(requestId = responseId, cause = t),
                usedAccessToken = session?.accessToken,
            )
        }

        // El cuerpo se conserva SIEMPRE, incluso cuando hay error: el sobre de
        // `GET /v1/health` llega con `503` y es una respuesta válida del
        // contrato, no un fallo de transporte. `send` usa el error; el health
        // usa el cuerpo.
        val body = HttpResponseBody(response.status.value, text, responseId)
        return if (response.status.isSuccess()) {
            Attempt(body = body, usedAccessToken = session?.accessToken)
        } else {
            Attempt(
                body = body,
                error = parseError(text, response.status.value, responseId),
                usedAccessToken = session?.accessToken,
            )
        }
    }

    private suspend fun execute(
        accessToken: String?,
        requestId: String,
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String?>>,
        body: String?,
        bodyBlock: (HttpRequestBuilder.() -> Unit)?,
        idempotencyKey: String?,
    ): HttpResponse? = try {
        http.request(config.resolve(path)) {
            this.method = method
            if (query.isNotEmpty()) {
                url {
                    query.forEach { (name, value) ->
                        // Un filtro nulo NO se envía: `?warehouseId=null` es una
                        // petición distinta de no filtrar, y el servidor la
                        // rechazaría con un 400.
                        if (value != null) parameters.append(name, value)
                    }
                }
            }
            if (idempotencyKey != null) header(StockHeaders.IDEMPOTENCY_KEY, idempotencyKey)
            header(StockHeaders.REQUEST_ID, requestId)
            header(StockHeaders.CLIENT_CHANNEL, config.channel.headerValue)
            header(StockHeaders.CLIENT_TIMEZONE, config.timezone)
            accessToken?.let { token -> header(HttpHeaders.Authorization, "Bearer $token") }
            when {
                body != null -> {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                // El multipart pone su propio `Content-Type` con la frontera;
                // forzar `application/json` aquí rompería la subida.
                bodyBlock != null -> bodyBlock()
            }
        }
    } catch (cancellation: CancellationException) {
        // La cancelación de una corrutina no es un fallo de red: propagarla es lo
        // correcto, y tragarla dejaría la pantalla esperando para siempre.
        throw cancellation
    } catch (t: Throwable) {
        null
    }

    /**
     * Traduce el cuerpo de error.
     *
     * Se intenta `ErrorDto` y, si la respuesta no lo trae (un proxy que devuelve
     * HTML, un `502` de la infraestructura), se clasifica por **estado HTTP**:
     * perder la información de que el servidor está caído sería peor que no
     * tener código.
     */
    private fun parseError(text: String, status: Int, requestId: String?): ApiError {
        if (text.isBlank()) return ApiError.fromStatus(status, requestId = requestId)
        val dto = runCatching { stockJson.decodeFromString<ErrorDto>(text) }.getOrNull()
            ?: return ApiError.fromStatus(
                status,
                message = text.take(MAX_ERROR_BODY_CHARS).takeIf { it.isNotBlank() },
                requestId = requestId,
            )
        return ApiError.from(dto, status, requestId)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // REFRESH: una sola vez, con exclusión mutua y sin recursión
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun refreshSession(staleAccessToken: String?): RefreshOutcome =
        refreshMutex.withLock {
            val current = tokens.load()

            // ── Single-flight ───────────────────────────────────────────────
            // Otra petición pudo refrescar mientras esta esperaba el mutex. En
            // ese caso el token guardado ya no es el que provocó el 401, así que
            // sirve tal cual y no se rota dos veces: rotar dos veces invalidaría
            // el token bueno y dejaría a la app con uno que el servidor ya no
            // reconoce.
            if (current != null && current.accessToken != staleAccessToken) {
                return@withLock RefreshOutcome.AlreadyRenewed
            }

            val refreshToken = current?.refreshToken
            if (refreshToken == null) {
                invalidateSession()
                return@withLock RefreshOutcome.Failed(null)
            }

            val refreshAttempt = attempt(
                method = HttpMethod.Post,
                path = REFRESH_PATH,
                query = emptyList(),
                body = stockJson.encodeToString(RefreshTokenRequest(refreshToken)),
                bodyBlock = null,
                idempotencyKey = null,
                // El refresh NO se autentica con el access token: se autentica
                // con el propio refresh token. Y al ir por `attempt` y no por
                // `send`, no puede disparar otro refresh: la recursión es
                // imposible por construcción.
                authenticated = false,
            )

            val failure = refreshAttempt.error
            if (failure != null) {
                if (failure !is ApiError.Network && failure !is ApiError.ServerUnavailable &&
                    failure !is ApiError.RateLimited
                ) {
                    invalidateSession()
                }
                return@withLock RefreshOutcome.Failed(failure)
            }

            val session = runCatching {
                stockJson.decodeFromString<AuthSessionDto>(refreshAttempt.body!!.text)
            }.getOrNull()

            if (session == null) {
                invalidateSession()
                return@withLock RefreshOutcome.Failed(null)
            }

            storeSession(session)
            RefreshOutcome.Renewed(session.accessToken)
        }

    /**
     * La sesión dejó de ser recuperable: se borra y se avisa a la app.
     *
     * Es `internal` porque el cambio de contraseña la usa: el servidor invalida
     * **todas** las sesiones, incluida la actual, así que el cliente tiene que
     * hacer lo mismo o se quedará con un token que ya no sirve.
     */
    internal suspend fun invalidateSession() {
        tokens.clear()
        onSessionExpired()
    }

    private class Attempt(
        val body: HttpResponseBody? = null,
        val error: ApiError? = null,
        val usedAccessToken: String? = null,
    ) {
        /**
         * El error manda sobre el cuerpo.
         *
         * Es la regla que evita que una respuesta de error con cuerpo se
         * confunda con un éxito: el cuerpo de un `401` es un `ErrorDto`, no el
         * recurso que se fue a buscar.
         */
        fun asOutcome(): ApiOutcome<HttpResponseBody> = when {
            error != null -> error.asLeft()
            body != null -> body.asRight()
            else -> ApiError.MalformedResponse().asLeft()
        }
    }

    private sealed interface RefreshOutcome {
        data class Renewed(val accessToken: String) : RefreshOutcome
        data object AlreadyRenewed : RefreshOutcome
        data class Failed(val error: ApiError?) : RefreshOutcome
    }

    private companion object {
        /** Ruta del refresh. Es el único endpoint que el cliente conoce por dentro. */
        const val REFRESH_PATH = "/auth/refresh"

        /** Tope del cuerpo de error que se copia al mensaje: un HTML de proxy no es un mensaje. */
        const val MAX_ERROR_BODY_CHARS = 300

        /** `filename="..."` del `Content-Disposition`, si lo trae. */
        fun fileNameOf(header: String?): String? {
            if (header == null) return null
            val quoted = Regex("""filename\s*=\s*"([^"]+)"""").find(header)?.groupValues?.get(1)
            return quoted ?: Regex("""filename\s*=\s*([^;]+)""").find(header)?.groupValues?.get(1)?.trim()
        }
    }
}

/**
 * Decodifica el cuerpo al tipo pedido.
 *
 * Es una función **de nivel superior** y no un método del cliente a propósito:
 * una función de extensión miembro exigiría tener el cliente como receptor
 * implícito en cada llamada (`with(http) { ... }`), y eso ensuciaría todas las
 * clases de API. Aquí el tipo lo pone quien llama (`decode<Page<ProductDto>>()`)
 * y el JSON es el mismo para todo el módulo.
 *
 * Un fallo de formato **no** se convierte en `Network`: es
 * [ApiError.MalformedResponse], porque «el servidor contestó algo que no
 * entiendo» y «no hay red» son incidencias distintas y se arreglan de forma
 * distinta.
 */
internal inline fun <reified T> ApiOutcome<HttpResponseBody>.decode(): ApiOutcome<T> =
    when (this) {
        is Either.Left -> this
        is Either.Right -> try {
            stockJson.decodeFromString<T>(value.text).asRight()
        } catch (t: Throwable) {
            ApiError.MalformedResponse(
                httpStatus = value.status,
                requestId = value.requestId,
                cause = t,
            ).asLeft()
        }
    }
