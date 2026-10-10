package dev.axzy.stock.application.shared

/**
 * The transaction boundary, as a port. The use case decides WHAT goes together; the adapter knows
 * HOW a transaction is opened and closed. That way `application` does not depend on `@Transactional`.
 */
interface TransactionRunner {

    /**
     * Runs [block] in ONE transaction. **A `Left` rolls it back**: what the block wrote before
     * failing does not remain. An exception also rolls it back and propagates.
     *
     * Everything a use case writes —the change and its audit record— goes in a single block:
     * either both stay or neither does.
     */
    fun <A> inTransaction(block: () -> Outcome<A>): Outcome<A>
}
