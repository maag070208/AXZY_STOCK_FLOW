package dev.axzy.stock.network.error

import dev.axzy.stock.network.dto.common.ErrorDto
import kotlinx.serialization.json.JsonObject

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ERROR TIPADO DEL TRANSPORTE
 *
 * **El cliente decide con `code`, nunca con `message`.** Por eso este archivo es
 * un `when` exhaustivo sobre los códigos del contrato y no una comparación de
 * textos: el mensaje se traduce y se reescribe, y un cliente que compara texto
 * se rompe en silencio el día que alguien corrige una tilde.
 *
 * Colapsar los cinco códigos de autorización en un «403 no autorizado» es el
 * defecto del legacy: el usuario recibe el mismo aviso y no sabe si tiene que
 * pedir un permiso, esperar, o llamar a otra persona. Aquí son cinco casos
 * distintos porque **la acción del usuario es distinta en cada uno**.
 *
 * Contrato: CONTRATO_API.md §1.2, §1.3 y §2.
 * ═══════════════════════════════════════════════════════════════════════════
 */
sealed class ApiError(
    /** Código estable del contrato. Es lo único con lo que se decide. */
    val code: String,
    /** Mensaje ya traducido por el servidor. **Solo para mostrar.** */
    override val message: String,
    /** Detalles por endpoint, tal como llegaron. */
    val details: JsonObject? = null,
    /** Código HTTP que lo produjo. `null` si la petición no llegó a salir. */
    val httpStatus: Int? = null,
    /** `X-Request-Id` de la respuesta: es lo que se pega en un informe de incidencia. */
    val requestId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * Indica si **tiene sentido reintentar**.
     *
     * Es lo que usa la cola de sincronización para decidir entre reintentar con
     * backoff o marcar la operación como fallida definitiva. Reintentar un
     * `SEGREGATION_OF_DUTIES` no lo va a resolver; reintentar un `RATE_LIMITED`
     * o un corte de red, sí.
     */
    open val isRetryable: Boolean get() = false

    // ── Identidad y acceso ──────────────────────────────────────────────────

    /** Falta el token o venció. La acción del usuario es **iniciar sesión**. */
    class Unauthenticated(
        message: String = "Tu sesión terminó. Vuelve a iniciar sesión.",
        details: JsonObject? = null,
        httpStatus: Int? = 401,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "UNAUTHENTICATED" }
    }

    /**
     * **Se detectó reuso del refresh token y se revocó toda la familia.**
     *
     * Es un código distinto de [InvalidCredentials] porque la interfaz debe decir
     * algo distinto: «tu sesión se cerró por seguridad», no «usuario o
     * contraseña incorrectos».
     */
    class TokenReused(
        message: String = "Tu sesión se cerró por seguridad. Vuelve a iniciar sesión.",
        details: JsonObject? = null,
        httpStatus: Int? = 401,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "TOKEN_REUSED" }
    }

    /**
     * Usuario **o** contraseña incorrectos.
     *
     * El servidor usa el mismo código para los dos a propósito, para no permitir
     * enumerar usuarios. La UI no debe intentar distinguirlos.
     */
    class InvalidCredentials(
        message: String = "Usuario o contraseña incorrectos.",
        details: JsonObject? = null,
        httpStatus: Int? = 401,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "INVALID_CREDENTIALS" }
    }

    /** La cuenta está desactivada. La acción es **hablar con un administrador**. */
    class AccountDisabled(
        message: String = "La cuenta está desactivada.",
        details: JsonObject? = null,
        httpStatus: Int? = 403,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "ACCOUNT_DISABLED" }
    }

    /**
     * Cuenta bloqueada por intentos fallidos.
     * `until` es el instante en que se desbloquea; sirve para decir **cuándo**.
     */
    class AccountLocked(
        val until: String? = null,
        message: String = "La cuenta está bloqueada temporalmente.",
        details: JsonObject? = null,
        httpStatus: Int? = 403,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "ACCOUNT_LOCKED" }
    }

    /** No tiene el permiso. La acción es **pedirlo a un administrador**. */
    class PermissionDenied(
        val permission: String? = null,
        message: String = "No tienes permiso para esta acción.",
        details: JsonObject? = null,
        httpStatus: Int? = 403,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "PERMISSION_DENIED" }
    }

    /**
     * Tiene el permiso, pero **no alcanza este recurso**.
     * La acción es pedir acceso a **esa bodega**, que no es lo mismo que pedir
     * el permiso: quien lo concede es otra persona y por otro camino.
     */
    class ScopeInsufficient(
        val scope: String? = null,
        val permission: String? = null,
        message: String = "No tienes acceso a este recurso.",
        details: JsonObject? = null,
        httpStatus: Int? = 403,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "SCOPE_INSUFFICIENT" }
    }

    /**
     * Una política ABAC lo prohíbe.
     * La acción del usuario es cambiar **cuándo** o **cómo** lo hace.
     */
    class PolicyDenied(
        val policyName: String? = null,
        val reason: String? = null,
        message: String = "Una política impide esta acción.",
        details: JsonObject? = null,
        httpStatus: Int? = 403,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "POLICY_DENIED" }
    }

    /**
     * No puede aprobar lo que él mismo capturó.
     * La acción es **que lo apruebe otra persona**: es el único de los cinco que
     * no se resuelve ni esperando ni pidiendo permisos.
     */
    class SegregationOfDuties(
        message: String = "No puedes aprobar lo que tú mismo capturaste. " +
            "Requiere la autorización de otra persona.",
        details: JsonObject? = null,
        httpStatus: Int? = 422,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "SEGREGATION_OF_DUTIES" }
    }

    // ── Recurso y estado ────────────────────────────────────────────────────

    /** El recurso no existe. Puede ser un `404` real o un `409 NOT_FOUND`. */
    class NotFound(
        val resource: String? = null,
        message: String = "El recurso no existe.",
        details: JsonObject? = null,
        httpStatus: Int? = 404,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "NOT_FOUND" }
    }

    /**
     * El estado impide la operación.
     *
     * [code] conserva el código concreto (`PRODUCT_HAS_STOCK`,
     * `DISCREPANCY_NOT_APPROVED`, `ORDER_ALREADY_CONFIRMED`…): colapsarlos en un
     * «conflicto» genérico obligaría a la UI a leer el mensaje, que es lo que
     * este archivo existe para evitar.
     */
    class Conflict(
        code: String,
        message: String = "La operación no es posible en el estado actual.",
        details: JsonObject? = null,
        httpStatus: Int? = 409,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(code, message, details, httpStatus, requestId, cause)

    /**
     * Faltan existencias.
     *
     * Tiene caso propio —y no es un `Conflict` más— porque la UI **ofrece el
     * faltante**: es un flujo de negocio con datos (`details.available`), no un
     * aviso de error.
     */
    class InsufficientStock(
        val available: Double? = null,
        val requested: Double? = null,
        val location: String? = null,
        message: String = "No hay existencias suficientes.",
        details: JsonObject? = null,
        httpStatus: Int? = 422,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        companion object { const val CODE = "INSUFFICIENT_STOCK" }
    }

    /**
     * `422`: viola una regla de negocio. [fieldErrors] son los `details` por
     * campo, ya normalizados a texto para poder pintarlos junto al formulario.
     */
    class Validation(
        code: String,
        val fieldErrors: Map<String, String> = emptyMap(),
        message: String = "Los datos no cumplen una regla de negocio.",
        details: JsonObject? = null,
        httpStatus: Int? = 422,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(code, message, details, httpStatus, requestId, cause)

    /** Demasiados intentos. Reintentar **solo** tras [retryAfterSeconds]. */
    class RateLimited(
        val retryAfterSeconds: Long? = null,
        message: String = "Demasiados intentos. Espera un momento.",
        details: JsonObject? = null,
        httpStatus: Int? = 429,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, details, httpStatus, requestId, cause) {
        override val isRetryable: Boolean get() = true
        companion object { const val CODE = "RATE_LIMITED" }
    }

    // ── Infraestructura ─────────────────────────────────────────────────────

    /**
     * Una dependencia caída (`503`) o un `5xx` sin cuerpo de error útil.
     *
     * Es reintentable: el móvil sin señal **tiene** que poder reintentar, y un
     * servidor caído no es un error del usuario.
     */
    class ServerUnavailable(
        code: String = CODE,
        message: String = "El servidor no está disponible. Se reintentará.",
        details: JsonObject? = null,
        httpStatus: Int? = null,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(code, message, details, httpStatus, requestId, cause) {
        override val isRetryable: Boolean get() = true
        companion object { const val CODE = "SERVICE_UNAVAILABLE" }
    }

    /**
     * No se pudo hablar con el servidor: sin red, DNS, TLS o timeout.
     *
     * **No tiene código del contrato** porque el servidor nunca contestó. Se le
     * da uno propio para que la UI pueda decir «revisa tu conexión» en lugar de
     * «error desconocido», y es reintentable por definición.
     */
    class Network(
        message: String = "No se pudo conectar al servidor. Revisa tu conexión.",
        httpStatus: Int? = null,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, null, httpStatus, requestId, cause) {
        override val isRetryable: Boolean get() = true
        companion object { const val CODE = "NETWORK" }
    }

    /**
     * La respuesta no se pudo interpretar (JSON inválido o forma inesperada).
     *
     * Es un error de contrato, no de usuario: se deja explícito para que no se
     * confunda con «sin conexión» y para que aparezca en los tests.
     */
    class MalformedResponse(
        message: String = "La respuesta del servidor no tiene el formato esperado.",
        httpStatus: Int? = null,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(CODE, message, null, httpStatus, requestId, cause) {
        companion object { const val CODE = "MALFORMED_RESPONSE" }
    }

    /**
     * Código del contrato que este cliente todavía no conoce.
     *
     * **Existe a propósito**: un cliente que revienta con un código nuevo obliga
     * a actualizar la app para poder recibir un error. Con este caso, el
     * servidor puede añadir códigos sin romper a los clientes desplegados.
     */
    class Unknown(
        code: String,
        message: String = "Ocurrió un error inesperado.",
        details: JsonObject? = null,
        httpStatus: Int? = null,
        requestId: String? = null,
        cause: Throwable? = null,
    ) : ApiError(code, message, details, httpStatus, requestId, cause)

    companion object {

        // ── Códigos del contrato que este cliente traduce ───────────────────
        const val CODE_UNAUTHENTICATED = "UNAUTHENTICATED"
        const val CODE_TOKEN_REUSED = "TOKEN_REUSED"
        const val CODE_INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
        const val CODE_ACCOUNT_DISABLED = "ACCOUNT_DISABLED"
        const val CODE_ACCOUNT_LOCKED = "ACCOUNT_LOCKED"
        const val CODE_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val CODE_SCOPE_INSUFFICIENT = "SCOPE_INSUFFICIENT"
        const val CODE_POLICY_DENIED = "POLICY_DENIED"
        const val CODE_POLICY_REQUIRED = "POLICY_REQUIRED"
        const val CODE_SEGREGATION_OF_DUTIES = "SEGREGATION_OF_DUTIES"
        const val CODE_NOT_FOUND = "NOT_FOUND"
        const val CODE_INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK"
        const val CODE_REASON_REQUIRED = "REASON_REQUIRED"
        const val CODE_RATE_LIMITED = "RATE_LIMITED"

        /**
         * Traduce un [ErrorDto] a un caso tipado.
         *
         * **Se lee `dto.code` y nada más.** El mensaje no participa en la
         * decisión, y hay un test que lo afirma: cambiando el texto del mensaje,
         * el caso tipado no cambia.
         */
        fun from(
            dto: ErrorDto,
            httpStatus: Int? = null,
            requestId: String? = null,
            cause: Throwable? = null,
        ): ApiError = when (dto.code) {
            CODE_UNAUTHENTICATED -> Unauthenticated(dto.message, dto.details, httpStatus, requestId, cause)
            CODE_TOKEN_REUSED -> TokenReused(dto.message, dto.details, httpStatus, requestId, cause)
            CODE_INVALID_CREDENTIALS -> InvalidCredentials(dto.message, dto.details, httpStatus, requestId, cause)
            CODE_ACCOUNT_DISABLED -> AccountDisabled(dto.message, dto.details, httpStatus, requestId, cause)
            CODE_ACCOUNT_LOCKED -> AccountLocked(
                until = dto.detailString("until"),
                message = dto.message,
                details = dto.details,
                httpStatus = httpStatus,
                requestId = requestId,
                cause = cause,
            )

            CODE_PERMISSION_DENIED -> PermissionDenied(
                permission = dto.detailString("permission") ?: dto.detailString("action"),
                message = dto.message,
                details = dto.details,
                httpStatus = httpStatus,
                requestId = requestId,
                cause = cause,
            )

            CODE_SCOPE_INSUFFICIENT -> ScopeInsufficient(
                scope = dto.detailString("scope"),
                permission = dto.detailString("permission"),
                message = dto.message,
                details = dto.details,
                httpStatus = httpStatus,
                requestId = requestId,
                cause = cause,
            )

            CODE_POLICY_DENIED -> PolicyDenied(
                policyName = dto.detailString("policy") ?: dto.detailString("policyName"),
                reason = dto.detailString("reason"),
                message = dto.message,
                details = dto.details,
                httpStatus = httpStatus,
                requestId = requestId,
                cause = cause,
            )

            CODE_SEGREGATION_OF_DUTIES -> SegregationOfDuties(
                message = dto.message,
                details = dto.details,
                httpStatus = httpStatus,
                requestId = requestId,
                cause = cause,
            )

            CODE_NOT_FOUND -> NotFound(
                resource = dto.detailString("resource") ?: dto.detailString("entity"),
                message = dto.message,
                details = dto.details,
                httpStatus = httpStatus,
                requestId = requestId,
                cause = cause,
            )

            CODE_INSUFFICIENT_STOCK -> InsufficientStock(
                available = dto.detailDouble("available"),
                requested = dto.detailDouble("requested"),
                location = dto.detailString("location"),
                message = dto.message,
                details = dto.details,
                httpStatus = httpStatus,
                requestId = requestId,
                cause = cause,
            )

            CODE_RATE_LIMITED -> RateLimited(
                retryAfterSeconds = dto.detailLong("retryAfter") ?: dto.detailLong("retryAfterSeconds"),
                message = dto.message,
                details = dto.details,
                httpStatus = httpStatus,
                requestId = requestId,
                cause = cause,
            )

            // ── Por estado HTTP, cuando el código no basta ──────────────────
            // Se clasifica por código ANTES que por estado: un `409` con código
            // propio (`PRODUCT_HAS_STOCK`) no debe perder su código.
            else -> when (httpStatus) {
                400, 422 -> Validation(
                    code = dto.code,
                    fieldErrors = dto.fieldErrors(),
                    message = dto.message,
                    details = dto.details,
                    httpStatus = httpStatus,
                    requestId = requestId,
                    cause = cause,
                )

                409 -> Conflict(
                    code = dto.code,
                    message = dto.message,
                    details = dto.details,
                    httpStatus = httpStatus,
                    requestId = requestId,
                    cause = cause,
                )

                403 -> PermissionDenied(
                    permission = null,
                    message = dto.message,
                    details = dto.details,
                    httpStatus = httpStatus,
                    requestId = requestId,
                    cause = cause,
                )

                404 -> NotFound(
                    resource = null,
                    message = dto.message,
                    details = dto.details,
                    httpStatus = httpStatus,
                    requestId = requestId,
                    cause = cause,
                )

                429 -> RateLimited(
                    retryAfterSeconds = dto.detailLong("retryAfter"),
                    message = dto.message,
                    details = dto.details,
                    httpStatus = httpStatus,
                    requestId = requestId,
                    cause = cause,
                )

                else -> Unknown(
                    code = dto.code,
                    message = dto.message,
                    details = dto.details,
                    httpStatus = httpStatus,
                    requestId = requestId,
                    cause = cause,
                )
            }
        }

        /**
         * Traduce una respuesta **que no trae `ErrorDto`** (un proxy que
         * devuelve HTML, un `502` de la infraestructura).
         *
         * Sin esto, un error de un intermediario se leería como «respuesta
         * malformada» y se perdería la información de que el servidor está caído.
         */
        fun fromStatus(
            httpStatus: Int,
            message: String? = null,
            requestId: String? = null,
            cause: Throwable? = null,
        ): ApiError = when {
            httpStatus == 401 -> Unauthenticated(
                message = message ?: "Tu sesión terminó. Vuelve a iniciar sesión.",
                httpStatus = httpStatus, requestId = requestId, cause = cause,
            )

            httpStatus == 403 -> PermissionDenied(
                message = message ?: "No tienes permiso para esta acción.",
                httpStatus = httpStatus, requestId = requestId, cause = cause,
            )

            httpStatus == 404 -> NotFound(
                message = message ?: "El recurso no existe.",
                httpStatus = httpStatus, requestId = requestId, cause = cause,
            )

            httpStatus == 429 -> RateLimited(
                message = message ?: "Demasiados intentos. Espera un momento.",
                httpStatus = httpStatus, requestId = requestId, cause = cause,
            )

            httpStatus >= 500 -> ServerUnavailable(
                message = message ?: "El servidor no está disponible. Se reintentará.",
                httpStatus = httpStatus, requestId = requestId, cause = cause,
            )

            else -> MalformedResponse(
                message = message ?: "La respuesta del servidor no tiene el formato esperado.",
                httpStatus = httpStatus, requestId = requestId, cause = cause,
            )
        }

        /**
         * Normaliza los `details` por campo a texto.
         *
         * Los `details` de validación pueden traer texto, número o lista de
         * textos; la UI solo necesita una frase por campo.
         */
        private fun ErrorDto.fieldErrors(): Map<String, String> =
            details.orEmpty().mapValues { (_, value) ->
                value.toString().trim('"')
            }
    }
}
