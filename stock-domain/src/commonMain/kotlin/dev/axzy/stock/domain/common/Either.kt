package dev.axzy.stock.domain.common

import dev.axzy.stock.domain.error.DomainError

/**
 * Result of a domain operation: either a value, or an error.
 *
 * A custom type is used instead of `kotlin.Result` because `Result` requires that
 * the error be a `Throwable`, and our domain errors **are not**
 * **exceptional**: "the quantity exceeds the invoice" is an expected business result.
 * Modeling it as an exception would lead to the `try/catch` everywhere that the legacy did,
 * and with it to silenced errors.
 *
 * A custom type is used instead of `arrow-kt` so as not to add a dependency
 * to the domain: `stock-domain` must remain pure Kotlin (see the architecture guard in
 * build.gradle.kts).
 */
sealed interface Either<out E, out A> {

    /** The success case. */
    data class Right<out A>(val value: A) : Either<Nothing, A>

    /** The error case. */
    data class Left<out E>(val error: E) : Either<E, Nothing>

    val isRight: Boolean get() = this is Right
    val isLeft: Boolean get() = this is Left

    /** The value, or `null` if it is an error. */
    fun getOrNull(): A? = (this as? Right)?.value

    /** The error, or `null` if it is successful. */
    fun errorOrNull(): E? = (this as? Left)?.error
}

// ── Constructors and utilities ─────────────────────────────────────────────

fun <A> A.asRight(): Either<Nothing, A> = Either.Right(this)

fun <E> E.asLeft(): Either<E, Nothing> = Either.Left(this)

/** Transforms the successful value, leaving the error untouched. */
inline fun <E, A, B> Either<E, A>.map(transform: (A) -> B): Either<E, B> = when (this) {
    is Either.Right -> Either.Right(transform(value))
    is Either.Left -> this
}

/** Chains another operation that can also fail. */
inline fun <E, A, B> Either<E, A>.flatMap(transform: (A) -> Either<E, B>): Either<E, B> =
    when (this) {
        is Either.Right -> transform(value)
        is Either.Left -> this
    }

/** Runs an action on the successful value, without transforming it. */
inline fun <E, A> Either<E, A>.onRight(action: (A) -> Unit): Either<E, A> {
    if (this is Either.Right) action(value)
    return this
}

/** Runs an action on the error. */
inline fun <E, A> Either<E, A>.onLeft(action: (E) -> Unit): Either<E, A> {
    if (this is Either.Left) action(error)
    return this
}

/**
 * Returns the value or throws. **Only for tests and for entry points where**
 * **the error has already been translated**: in the normal flow `when` is used on the
 * result, never this function.
 */
fun <E, A> Either<E, A>.getOrThrow(): A = when (this) {
    is Either.Right -> value
    is Either.Left -> throw IllegalStateException(
        "Expected a successful result but got: $error"
    )
}

/**
 * Requirement: if the condition holds, it continues with the value; if not, it fails with the
 * indicated error. It is the equivalent of `require` but returning [Either].
 */
inline fun <E, A> A.ensure(condition: Boolean, error: () -> E): Either<E, A> =
    if (condition) Either.Right(this) else Either.Left(error())

/** Same as [ensure] but with a condition computed on the value. */
inline fun <E, A> A.ensureThat(predicate: (A) -> Boolean, error: (A) -> E): Either<E, A> =
    if (predicate(this)) Either.Right(this) else Either.Left(error(this))

/** Shortcut for the domain's result type, which always fails with [DomainError]. */
typealias DomainResult<A> = Either<DomainError, A>
