package dev.axzy.stock.adapters.web.http

import com.fasterxml.jackson.annotation.JsonInclude
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.domain.common.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * El cuerpo de error, el mismo en todos los endpoints (docs/api/errores.md). El cliente decide con
 * `code`, nunca con `message`.
 */
data class ErrorBody(
    val code: String,
    val message: String,
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val details: Map<String, String> = emptyMap(),
)

/** Un fallo que interrumpe la petición antes de llegar al caso de uso (p. ej. al resolver al actor). */
class FailureException(val failure: Failure) : RuntimeException(failure.message)

/**
 * El código de estado dice qué hacer de inmediato: `401` manda a iniciar sesión y `403` a pedir un
 * permiso. Por eso no son intercambiables.
 */
fun FailureKind.toStatus(): HttpStatus = when (this) {
    FailureKind.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
    FailureKind.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
    FailureKind.FORBIDDEN -> HttpStatus.FORBIDDEN
    FailureKind.NOT_FOUND -> HttpStatus.NOT_FOUND
    FailureKind.CONFLICT -> HttpStatus.CONFLICT
    FailureKind.RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_ENTITY
}

fun Failure.toResponse(): ResponseEntity<Any> =
    ResponseEntity.status(kind.toStatus()).body(ErrorBody(code, message, details))

/** Traduce el resultado de un caso de uso: el éxito con [status] y [body], el fallo con su contrato. */
fun <A> Outcome<A>.toResponse(status: HttpStatus = HttpStatus.OK, body: (A) -> Any? = { it }): ResponseEntity<Any> =
    when (this) {
        is Either.Left -> error.toResponse()
        is Either.Right -> {
            val content = body(value)
            if (content == null || content == Unit) ResponseEntity.status(status).build()
            else ResponseEntity.status(status).body(content)
        }
    }

/** Éxito sin cuerpo (`204`). */
fun Outcome<Unit>.toNoContent(): ResponseEntity<Any> = toResponse(HttpStatus.NO_CONTENT) { null }
