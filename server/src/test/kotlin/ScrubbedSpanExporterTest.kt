package ch.nokillswit

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A span exporter that keeps what it is handed (`ScrubbedSpanExporterTest`); the provider below registers one as `collecting`. */
class CollectingSpanExporter : SpanExporter {
    val spans = CopyOnWriteArrayList<SpanData>()

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        this.spans += spans
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/** The SPI entry (`META-INF/services` in the test resources) that lets the real `getOpenTelemetry` wiring export into [lastCreated]. */
class CollectingSpanExporterProvider : ConfigurableSpanExporterProvider {
    override fun getName(): String = "collecting"

    override fun createExporter(config: ConfigProperties): SpanExporter = CollectingSpanExporter().also { lastCreated = it }

    companion object {
        @Volatile
        var lastCreated: CollectingSpanExporter? = null
    }
}

/**
 * Span export hygiene (`core`'s `ScrubbedSpanExporter`): the Ktor instrumentation records the RAW throwable of a failed request, whose
 * `exception.message` and `exception.stacktrace` (every "Caused by" message) would carry a value a database error echoed. Whatever
 * exporter `OTEL_TRACES_EXPORTER` selects must receive the exception's type only.
 */
class ScrubbedSpanExporterTest {
    private fun failure(marker: String) = RuntimeException("outer", IllegalStateException("rejected body $marker"))

    private fun SpanData.exceptionEvent() = events.single { it.name == "exception" }

    private fun assertScrubbed(exported: SpanData, marker: String) {
        val event = exported.exceptionEvent()
        val keys = event.attributes.asMap().keys.map { it.key }
        assertTrue("exception.type" in keys, "the type is kept: $keys")
        assertTrue("exception.message" !in keys && "exception.stacktrace" !in keys, "message and stack trace are gone: $keys")
        assertEquals("java.lang.RuntimeException", event.attributes.asMap().entries.single { it.key.key == "exception.type" }.value)
        assertEquals("POST", exported.attributes.asMap().entries.single { it.key.key == "http.request.method" }.value)
        assertEquals(StatusCode.ERROR, exported.status.statusCode)
        assertTrue(marker !in exported.toString() && marker !in event.attributes.toString() && marker !in exported.status.description)
    }

    @Test
    fun `an exception recorded on a span reaches the exporter as its type only`() {
        val marker = "SPAN-${UUID.randomUUID()}"
        val collected = CollectingSpanExporter()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(ScrubbedSpanExporter(collected))).build()
        try {
            val span = provider.get("test").spanBuilder("POST /thing").startSpan()
            span.setAttribute("http.request.method", "POST")
            span.recordException(failure(marker))
            span.setStatus(StatusCode.ERROR, "failed with $marker")
            span.end()
        } finally {
            provider.close()
        }
        assertScrubbed(collected.spans.single(), marker)
    }

    @Test
    fun `a request's query string never reaches the exporter, whichever attribute carries it`() {
        val marker = "TERM-${UUID.randomUUID()}"
        val collected = CollectingSpanExporter()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(ScrubbedSpanExporter(collected))).build()
        try {
            val span = provider.get("test").spanBuilder("GET /api/v1/rooms/{id}/search").startSpan()
            span.setAttribute("http.request.method", "GET")
            span.setAttribute("url.path", "/api/v1/rooms/7/search")
            span.setAttribute("url.query", "q=$marker&tag=$marker")
            span.setAttribute("url.full", "http://host/api/v1/rooms/7/search?q=$marker")
            span.setAttribute("http.url", "http://host/api/v1/rooms/7/search?q=$marker")
            span.setAttribute("http.target", "/api/v1/rooms/7/search?q=$marker")
            span.end()
        } finally {
            provider.close()
        }
        val exported = collected.spans.single()
        val keys = exported.attributes.asMap().keys.map { it.key }
        assertEquals(listOf("http.request.method", "url.path"), keys.sorted(), "only the query-free attributes stay")
        assertTrue(marker !in exported.attributes.toString(), "the term reached the exporter")
    }

    @Test
    fun `the SDK getOpenTelemetry builds wraps whichever exporter OTEL_TRACES_EXPORTER selects`() {
        val marker = "SPAN-${UUID.randomUUID()}"
        val previous = System.getProperty("otel.traces.exporter")
        System.setProperty("otel.traces.exporter", "collecting")
        try {
            val sdk = getOpenTelemetry("scrub-test")
            try {
                val span = sdk.getTracer("test").spanBuilder("POST /thing").startSpan()
                span.setAttribute("http.request.method", "POST")
                span.recordException(failure(marker))
                span.setStatus(StatusCode.ERROR, "failed with $marker")
                span.end()
                sdk.sdkTracerProvider.forceFlush().join(10, TimeUnit.SECONDS)
            } finally {
                sdk.close()
            }
        } finally {
            if (previous == null) System.clearProperty("otel.traces.exporter") else System.setProperty("otel.traces.exporter", previous)
        }
        assertScrubbed(assertNotNull(CollectingSpanExporterProvider.lastCreated).spans.single(), marker)
    }
}
