package ch.nokillswit.infra

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** How long [backgroundScope] waits for its coroutines to finish once cancelled: an in-flight query has released its connection by then. */
const val BACKGROUND_JOIN_TIMEOUT_MS = 2_000L

/**
 * The ONE way the application owns a background loop (today the telemetry sampler and the ADR expiry sweep):
 * a [SupervisorJob] scope named [name] that, on `ApplicationStopping`, first runs [beforeCancel] (state the loop's owner must close
 * while the loop still runs, e.g. a long-lived connection the loop holds), then cancels the scope and joins it. Each of the two waits is
 * bounded by [timeoutMillis] ([BACKGROUND_JOIN_TIMEOUT_MS]), and the cancel and the join always follow a [beforeCancel] that failed or
 * timed out. The worst case is serial: the scopes stop one after another, each up to two bounds (2 x 2 s). That runs before
 * `ApplicationStopped`, where the connection pool is disposed, so no loop can touch a disposed pool or outlive a test application's
 * class loader (the `IllegalAccessError` Adder's live sweep once died with inside `testApplication`). Cancelling without joining is the
 * bug this helper exists to prevent; do not build a scope by hand.
 */
fun Application.backgroundScope(
    name: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    timeoutMillis: Long = BACKGROUND_JOIN_TIMEOUT_MS,
    beforeCancel: suspend () -> Unit = {},
): CoroutineScope {
    val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName(name))
    monitor.subscribe(ApplicationStopping) {
        val root = checkNotNull(scope.coroutineContext[Job])
        try {
            runBlocking { withTimeoutOrNull(timeoutMillis) { beforeCancel() } }
        } finally {
            scope.cancel()
            runBlocking { withTimeoutOrNull(timeoutMillis) { root.join() } }
        }
    }
    return scope
}
