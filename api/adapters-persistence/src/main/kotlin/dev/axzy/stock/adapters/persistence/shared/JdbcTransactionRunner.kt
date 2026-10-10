package dev.axzy.stock.adapters.persistence.shared

import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.TransactionRunner
import org.springframework.transaction.support.TransactionTemplate

/**
 * The transaction boundary over Spring's transaction manager. `JdbcClient` uses the
 * connection bound to the thread, so everything that runs inside the block goes into the same one.
 */
class JdbcTransactionRunner(private val template: TransactionTemplate) : TransactionRunner {

    override fun <A> inTransaction(block: () -> Outcome<A>): Outcome<A> =
        template.execute { status ->
            val outcome = block()
            // An expected failure is not an exception, so Spring would not roll back on its own.
            if (outcome.isLeft) status.setRollbackOnly()
            outcome
        }!!
}
