package dev.axzy.stock.backend.error

import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONTRATO DE ERRORES DE LA API
 *
 * **Todo error tiene un código estable.** El cliente decide con el `code`, nunca
 * con el texto: un mensaje se traduce, se reescribe o cambia de idioma, y una
 * aplicación que compara cadenas de texto se rompe en silencio.
 *
 * Es la corrección de un defecto del legacy, donde los errores eran mensajes de
 * Prisma que llegaban al cliente con el SQL y los nombres de tabla incluidos.
 * ═══════════════════════════════════════════════════════════════════════════
 */

@Serializable
data class ErrorResponse(
    /** Código estable y en mayúsculas: `NOT_FOUND`, `PERMISSION_DENIED`. */
    val code: String,
    /** Mensaje para mostrar a la persona. Traducible, no comparable. */
    val message: String,
    /**
     * Detalle por campo, para errores de validación.
     *
     * Un formulario necesita saber **qué campo** falló para marcarlo; un mensaje
     * genérico obliga al usuario a adivinar.
     */
    val details: Map<String, String>? = null,
)

/**
 * La excepción base de la aplicación.
 *
 * Lleva el código HTTP y el código de error juntos, para que la capa HTTP no
 * tenga que traducir un tipo de dominio a un estado: esa traducción es donde se
 * pierden los matices (un `PolicyDenied` y un `ScopeInsufficient` son ambos 403,
 * pero significan cosas distintas y el cliente necesita distinguirlas).
 */
open class StockException(
    val code: String,
    override val message: String,
    val httpStatus: Int,
    val details: Map<String, String>? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 400: la petición está mal formada. */
class BadRequestException(
    code: String = "BAD_REQUEST",
    message: String,
    details: Map<String, String>? = null,
) : StockException(code, message, 400, details)

/** 401: no hay identidad válida. */
class UnauthorizedException(
    code: String = "UNAUTHENTICATED",
    message: String = "Se requiere autenticación.",
) : StockException(code, message, 401)

/**
 * 403: hay identidad pero no autorización.
 *
 * **Distingue el motivo**, porque la acción que debe tomar el usuario es
 * distinta: sin el permiso hay que pedirlo; fuera de alcance hay que pedir el
 * acceso a esa bodega; denegado por política hay que cambiar cuándo o cómo se
 * hace la operación.
 */
class ForbiddenException(
    code: String,
    message: String,
) : StockException(code, message, 403)

/** 404: el recurso no existe. */
class NotFoundException(
    message: String,
    code: String = "NOT_FOUND",
) : StockException(code, message, 404)

/** 409: el estado actual impide la operación. */
class ConflictException(
    code: String,
    message: String,
    details: Map<String, String>? = null,
) : StockException(code, message, 409, details)

/** 422: la petición está bien formada pero viola una regla de negocio. */
class UnprocessableException(
    code: String,
    message: String,
    details: Map<String, String>? = null,
) : StockException(code, message, 422, details)
