package dev.axzy.stock.network.error

import dev.axzy.stock.domain.common.Either

/**
 * El resultado de una llamada al API.
 *
 * Se reutiliza el [Either] del dominio en lugar de lanzar excepciones por el
 * mismo motivo por el que el dominio lo usa: **un error del servidor no es
 * excepcional**. `409 DISCREPANCY_NOT_APPROVED` es una respuesta de negocio
 * esperada y hay que pintarla en la pantalla, no atraparla.
 *
 * Obliga a manejarlo: un `when` sobre el resultado no compila sin tratar el caso
 * de error, y así no aparece el `catch {}` vacío que silenciaba fallos en el
 * legacy.
 */
typealias ApiOutcome<T> = Either<ApiError, T>
