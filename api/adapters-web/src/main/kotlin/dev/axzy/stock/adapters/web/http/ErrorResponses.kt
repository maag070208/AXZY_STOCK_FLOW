package dev.axzy.stock.adapters.web.http

import com.fasterxml.jackson.annotation.JsonInclude
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.domain.common.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * The error body, the same on all endpoints (docs/api/errores.md). The client decides with
 * `code`, never with `message`.
 */
data class ErrorBody(
    val code: String,
    val message: String,
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val details: Map<String, String> = emptyMap(),
)

/** A failure that interrupts the request before it reaches the use case (e.g. when resolving the actor). */
class FailureException(val failure: Failure) : RuntimeException(failure.message)

/**
 * The status code says what to do immediately: `401` sends the user to sign in and `403` to request a
 * permission. That is why they are not interchangeable.
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

/** Translates a use case's result: success with [status] and [body], failure with its contract. */
fun <A> Outcome<A>.toResponse(status: HttpStatus = HttpStatus.OK, body: (A) -> Any? = { it }): ResponseEntity<Any> =
    when (this) {
        is Either.Left -> error.toResponse()
        is Either.Right -> {
            val content = body(value)
            if (content == null || content == Unit) ResponseEntity.status(status).build()
            else ResponseEntity.status(status).body(content)
        }
    }

/** Success with no body (`204`). */
fun Outcome<Unit>.toNoContent(): ResponseEntity<Any> = toResponse(HttpStatus.NO_CONTENT) { null }
