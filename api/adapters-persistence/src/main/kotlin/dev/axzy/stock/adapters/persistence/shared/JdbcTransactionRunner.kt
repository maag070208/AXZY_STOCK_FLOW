package dev.axzy.stock.adapters.persistence.shared

import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.TransactionRunner
import org.springframework.transaction.support.TransactionTemplate

/**
 * La frontera de transacción sobre el gestor de transacciones de Spring. `JdbcClient` usa la
 * conexión ligada al hilo, así que todo lo que se ejecute dentro del bloque va en la misma.
 */
class JdbcTransactionRunner(private val template: TransactionTemplate) : TransactionRunner {

    override fun <A> inTransaction(block: () -> Outcome<A>): Outcome<A> =
        template.execute { status ->
            val outcome = block()
            // Un fallo esperado no es una excepción, así que Spring no revertiría por sí solo.
            if (outcome.isLeft) status.setRollbackOnly()
            outcome
        }!!
}
