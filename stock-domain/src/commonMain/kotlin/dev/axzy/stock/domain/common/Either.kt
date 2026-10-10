package dev.axzy.stock.domain.common

import dev.axzy.stock.domain.error.DomainError

/**
 * Resultado de una operación del dominio: o un valor, o un error.
 *
 * Se usa un tipo propio en lugar de `kotlin.Result` porque `Result` obliga a
 * que el error sea un `Throwable`, y nuestros errores de dominio **no son
 * excepcionales**: "la cantidad excede la factura" es un resultado de negocio
 * esperado. Modelarlo como excepción llevaría al `try/catch` por todas partes
 * que hacía el legacy, y con él a los errores silenciados.
 *
 * Se usa un tipo propio en lugar de `arrow-kt` para no añadir una dependencia
 * al dominio: `stock-domain` debe seguir siendo Kotlin puro (ver la guardia de
 * arquitectura en build.gradle.kts).
 */
sealed interface Either<out E, out A> {

    /** El caso exitoso. */
    data class Right<out A>(val value: A) : Either<Nothing, A>

    /** El caso de error. */
    data class Left<out E>(val error: E) : Either<E, Nothing>

    val isRight: Boolean get() = this is Right
    val isLeft: Boolean get() = this is Left

    /** El valor, o `null` si es un error. */
    fun getOrNull(): A? = (this as? Right)?.value

    /** El error, o `null` si es exitoso. */
    fun errorOrNull(): E? = (this as? Left)?.error
}

// ── Constructores y utilidades ──────────────────────────────────────────────

fun <A> A.asRight(): Either<Nothing, A> = Either.Right(this)

fun <E> E.asLeft(): Either<E, Nothing> = Either.Left(this)

/** Transforma el valor exitoso, dejando el error intacto. */
inline fun <E, A, B> Either<E, A>.map(transform: (A) -> B): Either<E, B> = when (this) {
    is Either.Right -> Either.Right(transform(value))
    is Either.Left -> this
}

/** Encadena otra operación que también puede fallar. */
inline fun <E, A, B> Either<E, A>.flatMap(transform: (A) -> Either<E, B>): Either<E, B> =
    when (this) {
        is Either.Right -> transform(value)
        is Either.Left -> this
    }

/** Ejecuta una acción sobre el valor exitoso, sin transformarlo. */
inline fun <E, A> Either<E, A>.onRight(action: (A) -> Unit): Either<E, A> {
    if (this is Either.Right) action(value)
    return this
}

/** Ejecuta una acción sobre el error. */
inline fun <E, A> Either<E, A>.onLeft(action: (E) -> Unit): Either<E, A> {
    if (this is Either.Left) action(error)
    return this
}

/**
 * Devuelve el valor o lanza. **Solo para tests y para puntos de entrada donde
 * el error ya se ha traducido**: en el flujo normal se usa `when` sobre el
 * resultado, nunca esta función.
 */
fun <E, A> Either<E, A>.getOrThrow(): A = when (this) {
    is Either.Right -> value
    is Either.Left -> throw IllegalStateException(
        "Expected a successful result but got: $error"
    )
}

/**
 * Requisito: si la condición se cumple, sigue con el valor; si no, falla con el
 * error indicado. Es el equivalente a `require` pero devolviendo [Either].
 */
inline fun <E, A> A.ensure(condition: Boolean, error: () -> E): Either<E, A> =
    if (condition) Either.Right(this) else Either.Left(error())

/** Igual que [ensure] pero con una condición calculada sobre el valor. */
inline fun <E, A> A.ensureThat(predicate: (A) -> Boolean, error: (A) -> E): Either<E, A> =
    if (predicate(this)) Either.Right(this) else Either.Left(error(this))

/** Atajo para el tipo de resultado del dominio, que siempre falla con [DomainError]. */
typealias DomainResult<A> = Either<DomainError, A>
