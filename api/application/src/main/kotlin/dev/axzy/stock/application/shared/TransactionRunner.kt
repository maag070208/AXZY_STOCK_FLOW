package dev.axzy.stock.application.shared

/**
 * La frontera de transacción, como puerto. El caso de uso decide QUÉ va junto; el adaptador sabe
 * CÓMO se abre y se cierra una transacción. Así `application` no depende de `@Transactional`.
 */
interface TransactionRunner {

    /**
     * Ejecuta [block] en UNA transacción. **Un `Left` la revierte**: lo que el bloque haya escrito
     * antes de fallar no queda. Una excepción también la revierte y se propaga.
     *
     * Todo lo que un caso de uso escribe —el cambio y su registro de bitácora— va dentro de un
     * solo bloque: o quedan los dos o no queda ninguno.
     */
    fun <A> inTransaction(block: () -> Outcome<A>): Outcome<A>
}
