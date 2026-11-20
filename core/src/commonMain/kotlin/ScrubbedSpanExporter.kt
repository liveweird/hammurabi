package ch.nokillswit

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SpanExporter

private val EXCEPTION_MESSAGE: AttributeKey<String> = AttributeKey.stringKey("exception.message")
private val EXCEPTION_STACKTRACE: AttributeKey<String> = AttributeKey.stringKey("exception.stacktrace")

/**
 * The attributes the HTTP instrumentations fill with a request's QUERY STRING (`url.query` is the one Ktor's server spans carry; the
 * others are the older and the full-URL spellings): a search term and a filter value travel there.
 */
private val QUERY_ATTRIBUTES: List<AttributeKey<String>> =
    listOf("url.query", "url.full", "http.url", "http.target").map { AttributeKey.stringKey(it) }

/**
 * Wraps every span exporter the SDK configures (`getOpenTelemetry`) so a recorded exception reaches the collector as its type only.
 * The Ktor instrumentation hands the RAW throwable of a failed request to `Span.recordException`, which writes `exception.message` and
 * `exception.stacktrace` (every "Caused by" message included); a database exception's message can carry bound values (a message body,
 * a search term), the very thing the 500 catch-all scrubs from the log (`plugins/ScrubbedFailure.kt`). Setting
 * `OTEL_TRACES_EXPORTER=otlp` must not route them to a collector, so the two attributes are dropped from every event and a status
 * description is emptied; `exception.type` and every other attribute stay. The request's query string goes too ([QUERY_ATTRIBUTES]):
 * it carries what a user typed into a filter or a search, which no other telemetry surface carries, so it never reaches a collector
 * (the path and the route, which hold ids only, stay).
 */
class ScrubbedSpanExporter(private val delegate: SpanExporter) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode = delegate.export(spans.map(::ScrubbedSpanData))

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()

    override fun close() = delegate.close()
}

private class ScrubbedSpanData(private val span: SpanData) : DelegatingSpanData(span) {
    override fun getAttributes(): Attributes {
        val attributes = span.attributes
        if (QUERY_ATTRIBUTES.none { attributes.get(it) != null }) return attributes
        val builder = attributes.toBuilder()
        QUERY_ATTRIBUTES.forEach { builder.remove(it) }
        return builder.build()
    }

    override fun getEvents(): List<EventData> = span.events.map { event ->
        val attributes = event.attributes
        if (attributes.get(EXCEPTION_MESSAGE) == null && attributes.get(EXCEPTION_STACKTRACE) == null) {
            event
        } else {
            val kept = attributes.toBuilder().remove(EXCEPTION_MESSAGE).remove(EXCEPTION_STACKTRACE).build()
            EventData.create(event.epochNanos, event.name, kept, event.totalAttributeCount)
        }
    }

    override fun getStatus(): StatusData =
        if (span.status.description.isEmpty()) span.status else StatusData.create(span.status.statusCode, "")
}
