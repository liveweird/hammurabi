package ch.nokillswit

import ch.nokillswit.infra.backgroundScope
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `infra/BackgroundScope.kt`: the one owner of the application's background loops (today the telemetry sampler). On
 * `ApplicationStopping` it runs `beforeCancel`, cancels the scope and joins it (bounded), so a loop is finished before the pool is
 * disposed on `ApplicationStopped` and cannot outlive a test application's class loader (the `LiveSweep` `IllegalAccessError` flake
 * Adder saw). The telemetry wiring's own proof is `TelemetryWiringTest`.
 */
class BackgroundScopeTest {
    private companion object {
        const val SHORT_BOUND_MS = 200L
    }

    /** Boots an application whose `application {}` block calls [setUp]; returns after the application has stopped. */
    private fun bootAndStop(setUp: Application.() -> Unit) = testApplication {
        configureApp()
        application { setUp() }
        startApplication()
    }

    @Test
    fun `a stopped application has run beforeCancel first, then finished every coroutine of the scope`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        lateinit var scope: CoroutineScope
        bootAndStop {
            scope = backgroundScope("test-loop") {
                // The loop is still running while beforeCancel runs: it may close what the loop uses.
                order += "beforeCancel active=${scope.coroutineContext[Job]?.isActive}"
            }
            // Enter the try block before shutdown can cancel the child.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    order += "loop cancelled"
                }
            }
        }
        val job = assertNotNull(scope.coroutineContext[Job])
        assertTrue(job.isCompleted, "ApplicationStopping cancelled the scope and joined it")
        assertEquals(listOf("beforeCancel active=true", "loop cancelled"), order.toList())
    }

    @Test
    fun `the join is bounded - a coroutine that ignores cancellation does not hold the stop`() {
        val release = CompletableDeferred<Unit>()
        val inside = CompletableDeferred<Unit>()
        lateinit var scope: CoroutineScope
        var stoppedAtNanos = 0L
        try {
            testApplication {
                configureApp()
                application {
                    scope = backgroundScope("stubborn-loop", timeoutMillis = SHORT_BOUND_MS)
                    scope.launch { withContext(NonCancellable) { inside.complete(Unit); release.await() } }
                }
                startApplication()
                runBlocking { withTimeout(10_000) { inside.await() } } // the child is in its uncancellable block before the stop
                stoppedAtNanos = System.nanoTime()
            }
            val tookMillis = (System.nanoTime() - stoppedAtNanos) / 1_000_000
            assertFalse(assertNotNull(scope.coroutineContext[Job]).isCompleted, "the stubborn child outlived the bounded join")
            assertTrue(tookMillis >= SHORT_BOUND_MS, "the stop waited the bound ($tookMillis ms) and then gave up")
        } finally {
            release.complete(Unit)
            runBlocking { scope.coroutineContext[Job]?.join() }
        }
    }

    @Test
    fun `a beforeCancel that never returns is bounded too, and the scope is still cancelled`() {
        lateinit var scope: CoroutineScope
        bootAndStop {
            scope = backgroundScope("hung-hook", timeoutMillis = SHORT_BOUND_MS) { awaitCancellation() }
            scope.launch { awaitCancellation() }
        }
        assertTrue(assertNotNull(scope.coroutineContext[Job]).isCompleted)
    }

    @Test
    fun `a beforeCancel that fails still cancels and joins the scope`() {
        lateinit var scope: CoroutineScope
        // Ktor may surface the failed handler from the stop; either way the scope must be finished.
        runCatching {
            bootAndStop {
                scope = backgroundScope("failing-hook") { error("close failed") }
                scope.launch { awaitCancellation() }
            }
        }
        assertTrue(assertNotNull(scope.coroutineContext[Job]).isCompleted)
    }

    @Test
    fun `no main source builds a coroutine scope by hand, nor uses GlobalScope - the background loops go through backgroundScope`() {
        val root = File(System.getProperty("repo.root") ?: "..", "server/src/main/kotlin")
        assertTrue(root.isDirectory, "${root.absolutePath} not found")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "BackgroundScope.kt" }
            .filter { Regex("""\b(CoroutineScope|MainScope)\(|\bGlobalScope\b""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList(), offenders)
    }
}
