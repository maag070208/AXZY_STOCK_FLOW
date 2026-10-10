package dev.axzy.stock.testing

import dev.axzy.stock.adapters.persistence.shared.JdbcTransactionRunner
import dev.axzy.stock.application.shared.TransactionRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * What is needed to build the persistence adapters by hand in a test, the same way
 * that `app` wires them: the JDBC client, the transaction boundary and the JSON serializer.
 *
 * Works over the API connection ([TestDatabase.api]) and counts the statements that run.
 */
class TestPersistence {
    val statements = StatementCounter(TestDatabase.api)
    val jdbc: JdbcClient = JdbcClient.create(statements)
    val json: JsonMapper = jsonMapper { addModule(kotlinModule()) }

    private val transactionManager = DataSourceTransactionManager(statements)
    val tx: TransactionRunner = JdbcTransactionRunner(TransactionTemplate(transactionManager))

    /** A transaction separate from the one in progress, like the one the denial log uses. */
    val independentTx = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** To prepare data and make checks that the API does not have permission to make. */
    val asOwner: JdbcClient = JdbcClient.create(TestDatabase.owner)
}

/**
 * Counts the SQL statements that are prepared. It checks that a listing makes a
 * FIXED number of queries (rule R-P1): if the number grows with the rows, there is a query in a loop.
 */
class StatementCounter(delegate: DataSource) : DelegatingDataSource(delegate) {
    private val prepared = AtomicInteger()

    override fun getConnection(): Connection {
        val real = super.getConnection()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement" || method.name == "prepareCall") prepared.incrementAndGet()
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
    }

    /** Runs [block] and returns how many statements it prepared. */
    fun during(block: () -> Unit): Int {
        val before = prepared.get()
        block()
        return prepared.get() - before
    }
}
