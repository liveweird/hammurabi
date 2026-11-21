package ch.nokillswit

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A test seam for "which statements did this service run": an UNPOOLED connection to the shared test container whose every
 * `createStatement(sql)` is written to [statements] before it is delegated. Hand it to a service built by hand (`AdrListService`,
 * `AdrEventService`) and read the SQL back, so a test can pin the SHAPE of a query (the columns it selects, that a probe is bounded)
 * where the answer alone cannot tell a bounded read from an unbounded one.
 */
class AdrStatementRecorder {
    private val recorded = CopyOnWriteArrayList<String>()

    /** Every statement text run through [database] so far, in order. */
    val statements: List<String> get() = recorded.toList()

    val database: R2dbcDatabase by lazy {
        val options = ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl)
            .mutate()
            .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
            .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
            .build()
        val config = R2dbcDatabaseConfig.Builder().apply {
            connectionFactoryOptions = options
            defaultMaxAttempts = 1
        }
        R2dbcDatabase.connect(connectionFactory = Recording(ConnectionFactories.get(options)), databaseConfig = config)
    }

    private inner class Recording(private val delegate: ConnectionFactory) : ConnectionFactory by delegate {
        override fun create(): Publisher<out Connection> = Mono.from(delegate.create()).map { connection -> recording(connection) }
    }

    private fun recording(connection: Connection): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "createStatement" && args?.size == 1) recorded += args[0] as String
            try {
                if (args == null) method.invoke(connection) else method.invoke(connection, *args)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection
}
