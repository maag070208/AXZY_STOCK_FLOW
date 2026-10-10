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
 * Lo que hace falta para construir a mano los adaptadores de persistencia en una prueba, igual
 * que los cablea `app`: el cliente JDBC, la frontera de transacción y el serializador JSON.
 *
 * Trabaja sobre la conexión de la API ([TestDatabase.api]) y cuenta las sentencias que se ejecutan.
 */
class TestPersistence {
    val statements = StatementCounter(TestDatabase.api)
    val jdbc: JdbcClient = JdbcClient.create(statements)
    val json: JsonMapper = jsonMapper { addModule(kotlinModule()) }

    private val transactionManager = DataSourceTransactionManager(statements)
    val tx: TransactionRunner = JdbcTransactionRunner(TransactionTemplate(transactionManager))

    /** Una transacción aparte de la que esté en curso, como la que usa el registro de denegaciones. */
    val independentTx = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** Para preparar datos y hacer comprobaciones que la API no tiene permiso de hacer. */
    val asOwner: JdbcClient = JdbcClient.create(TestDatabase.owner)
}

/**
 * Cuenta las sentencias SQL que se preparan. Sirve para comprobar que un listado hace un número
 * FIJO de consultas (regla R-P1): si el número crece con las filas, hay una consulta en un bucle.
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

    /** Ejecuta [block] y devuelve cuántas sentencias preparó. */
    fun during(block: () -> Unit): Int {
        val before = prepared.get()
        block()
        return prepared.get() - before
    }
}
