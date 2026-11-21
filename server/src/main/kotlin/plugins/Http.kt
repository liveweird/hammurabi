package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.hsts.*
import io.ktor.server.plugins.httpsredirect.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.mutableOriginConnectionPoint
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER

/**
 * Global request-body ceiling: a memory-DoS backstop, not a business rule. 512 KiB is far above any legitimate body of the foundation
 * (a user, a team, a feature-flag set are a few hundred bytes) and far below what lets a few concurrent callers pin real heap, since
 * the body is buffered before it decodes. Every payload field already carries its own maxLength. A feature whose documents outgrow it
 * (an ADR body) sizes its own ceiling in [requestBodyLimit] from its field maxima, the way Adder sized its thread and message writes
 * (that arithmetic is in Adder's `plugins/Http.kt`). Exceeding it answers 413 (problem body via ErrorHandling.kt).
 */
const val MAX_REQUEST_BODY_BYTES: Long = 512L * 1024

/**
 * The tight ceiling of the routes whose bodies are a handful of short fields: the anonymous auth routes (an unauthenticated caller can
 * reach them, so they must not buffer half a megabyte per request). Same 413.
 */
const val MAX_SMALL_REQUEST_BODY_BYTES: Long = 16L * 1024

/**
 * The ceiling of an ADR document write (`POST /api/v1/adrs`, `PUT /api/v1/adrs/{id}`), sized from its big fields: the problem, context,
 * decision, alternatives and consequences carry at most 20,000 UTF-16 units each (`MAX_ADR_TEXT_LENGTH`) after normalisation, and a raw
 * non-ASCII BMP unit is at most 3 bytes in UTF-8 (a line feed or tab is 2 as the JSON escape), so 300,000 bytes; the name adds at most 360,
 * the expiry date a dozen and a create's at most 99 initial participants (`{"userId":4294967295,"role":"CONTRIBUTOR"}`, 43 bytes with the
 * comma) about 4,300 - roughly 305 KB in all. 384 KiB (393,216 bytes) leaves a quarter of headroom for the CRLF pairs and trailing
 * whitespace normalisation drops, and sits below [MAX_REQUEST_BODY_BYTES] (the body is buffered before it decodes, so the ceiling bounds
 * what a few concurrent writers pin). Not covered, on purpose: a client that `\u`-escapes every non-ASCII character of five maximal
 * fields (6 bytes a unit, about 600 KB), which no real client sends. Same 413.
 */
const val MAX_ADR_REQUEST_BODY_BYTES: Long = 384L * 1024

/**
 * The ceiling of a discussion entry write (`POST /api/v1/adrs/{id}/discussion`, `PUT /api/v1/adrs/{id}/discussion/{entryId}`): the body is
 * at most 10,000 UTF-16 units (`MAX_DISCUSSION_BODY_LENGTH`) after normalisation. The worst honest request is a client that `\u`-escapes
 * every unit (6 bytes a unit, 60,000), or one that sends CRLF pairs as JSON escapes (4 bytes per normalised line feed, 40,000); raw UTF-8
 * is at most 3 bytes a unit (30,000). Add the few dozen bytes of the object: 64 KiB (65,536 bytes) clears all of them with headroom and
 * sits far below [MAX_REQUEST_BODY_BYTES]. Same 413.
 */
const val MAX_DISCUSSION_REQUEST_BODY_BYTES: Long = 64L * 1024

private val SMALL_BODY_PATHS = setOf(
    "/api/v1/login",
    "/api/v1/login/mfa",
    "/api/v1/refresh",
    "/api/v1/password-reset",
    "/api/v1/password-reset/confirm",
)

private val ADR_DOCUMENT_PATH = Regex("^/api/v1/adrs(/[0-9]+)?$")

/**
 * The ADR transition `{to}`, the participants PUT (at most 100 `{userId, role}` entries, about 4.5 KB) and the successor acceptance
 * `{successorId}`: small bodies.
 */
private val ADR_SMALL_PATH = Regex("^/api/v1/adrs/[0-9]+/(transitions|participants|successor/accept)$")

/** A discussion entry's text: `POST .../discussion` (post) and `PUT .../discussion/{entryId}` (edit). */
private val DISCUSSION_ENTRY_PATH = Regex("^/api/v1/adrs/[0-9]+/discussion(/[0-9]+)?$")

/** The state PUT: `{status, resolution?}` with a note of at most 2,000 characters (about 8 KB at worst): a small body. */
private val DISCUSSION_STATE_PATH = Regex("^/api/v1/adrs/[0-9]+/discussion/[0-9]+/state$")

/** The discussion writes' ceilings (a separate function keeps [requestBodyLimit] flat); null = not a discussion write. */
private fun discussionBodyLimit(method: HttpMethod, path: String): Long? = when {
    method == HttpMethod.Put && DISCUSSION_STATE_PATH.matches(path) -> MAX_SMALL_REQUEST_BODY_BYTES
    (method == HttpMethod.Post || method == HttpMethod.Put) && DISCUSSION_ENTRY_PATH.matches(path) -> MAX_DISCUSSION_REQUEST_BODY_BYTES
    else -> null
}

/**
 * The path as ROUTING sees it, which is what the ceilings must match: the raw request path split on `/`, empty segments dropped (routing
 * ignores a trailing or doubled slash) and every segment percent-decoded (`/api/v1/%6Cogin` is `/api/v1/login` to routing). Matching
 * the raw text let an encoded letter slip past every ceiling to the 512 KiB global one. A segment that does not decode cannot route
 * either, so the raw path is returned and the call keeps the global ceiling.
 */
internal fun routedPath(rawPath: String): String = try {
    rawPath.split('/').filter { it.isNotEmpty() }.joinToString("/", prefix = "/") { it.decodeURLPart() }
} catch (_: URLDecodeException) {
    rawPath
}

/**
 * The body ceiling of this call: [MAX_SMALL_REQUEST_BODY_BYTES] for the anonymous auth POSTs, the ADR transition and participants
 * writes and a discussion state PUT, [MAX_ADR_REQUEST_BODY_BYTES] for an ADR create or document PUT, [MAX_DISCUSSION_REQUEST_BODY_BYTES]
 * for a discussion entry post or edit, else [MAX_REQUEST_BODY_BYTES]. Matched against the
 * [routedPath], so an encoded spelling of the same route gets the same ceiling.
 */
internal fun requestBodyLimit(call: ApplicationCall): Long {
    val method = call.request.httpMethod
    val path = routedPath(call.request.path())
    return when {
        method == HttpMethod.Post && path in SMALL_BODY_PATHS -> MAX_SMALL_REQUEST_BODY_BYTES
        method == HttpMethod.Post && ADR_SMALL_PATH.matches(path) -> MAX_SMALL_REQUEST_BODY_BYTES
        method == HttpMethod.Put && ADR_SMALL_PATH.matches(path) -> MAX_SMALL_REQUEST_BODY_BYTES
        method == HttpMethod.Post && path == "/api/v1/adrs" -> MAX_ADR_REQUEST_BODY_BYTES
        method == HttpMethod.Put && ADR_DOCUMENT_PATH.matches(path) -> MAX_ADR_REQUEST_BODY_BYTES
        else -> discussionBodyLimit(method, path) ?: MAX_REQUEST_BODY_BYTES
    }
}

/**
 * Whether Swagger UI + the spec are served: `http.exposeOpenApi` when set, else development mode. ONE
 * definition for both the route install (`Routing.kt`) and the CSP exemption
 * (`SecurityHeaders.kt`) — the exemption must never apply where the UI is not actually mounted.
 */
internal fun Application.exposesOpenApi(): Boolean =
    environment.config.propertyOrNull("http.exposeOpenApi")?.getString()
        ?.takeIf { it.isNotBlank() }?.toBoolean()
        ?: developmentMode

fun Application.configureHttp() {
    install(RequestBodyLimit) {
        bodyLimit(::requestBodyLimit)
    }
    install(CachingHeaders) {
        options { call, outgoingContent ->
            when (outgoingContent.contentType?.withoutParameters()) {
                // The SPA's static assets are content-hashed by Vite, so a day-long cache is
                // safe for both stylesheets and scripts (both JS media types — the served one
                // depends on the container's mime mapping).
                ContentType.Text.CSS,
                ContentType.Text.JavaScript,
                ContentType.Application.JavaScript,
                -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60))
                // index.html (and every SPA deep link answered with it) is the un-hashed entry point that must
                // pick up new asset names on deploy: `no-cache` forbids reusing a stored copy without asking the
                // server first, so a deploy is never masked by a stale shell. (ConditionalHeaders is not installed, so
                // the revalidation is a plain re-fetch of this one small file, not a 304.) Without this entry the
                // header was simply absent and browsers fell back to heuristic caching.
                ContentType.Text.Html -> CachingOptions(CacheControl.NoCache(null))
                // API answers (JSON and RFC 7807 problem+json) carry per-user, bearer-authorized data:
                // `no-store` keeps them out of browser/proxy caches (a shared machine's back button, an
                // intermediary replaying one user's list to another). Scoped to /api/ so a static JSON
                // asset the SPA ships (if one ever appears) is not swept up (`.claude/docs/security.md` "Caching").
                // `problem+json` is `no-store` too.
                ContentType.Application.Json ->
                    if (call.request.path().startsWith("/api/")) CachingOptions(CacheControl.NoStore(null)) else null
                ContentType.Application.ProblemJson ->
                    if (call.request.path().startsWith("/api/")) CachingOptions(CacheControl.NoStore(null)) else null
                else -> null
            }
        }
    }
    // The client address every per-IP bucket (rate limits) keys by is `origin.remoteHost`. On Netty that is
    // `InetSocketAddress.getHostName()`: a REVERSE-DNS lookup, so a client with forward-confirmed PTR records for many addresses gets a
    // different key per address, and one whose PTR is shared (or whose lookup times out) shares one. The numeric address
    // (`remoteAddress`, `getHostString()`) is the key; it is copied FIRST (Setup phase, before anything reads `remoteHost`: the JDK
    // caches the resolved name), and the proxy-trust interceptor below overrides it with the trusted hop.
    intercept(ApplicationCallPipeline.Setup) {
        call.mutableOriginConnectionPoint.remoteHost = numericClientHost(call.request.local)
    }
    // No route accepts a request body that carries a `Content-Encoding` (other than `identity`): it is refused here, before any ceiling,
    // WAF or parser reads it, so a compressed or merely labelled body is never decoded and never parsed (checkup C-SEC-1; the 415
    // vocabulary of a wrong media type). `Compression` below is response-only for the same reason.
    intercept(ApplicationCallPipeline.Setup) {
        if (hasEncodedBody(call.request.headers.getAll(HttpHeaders.ContentEncoding))) {
            call.respondProblem(HttpStatusCode.UnsupportedMediaType, "Request bodies must not be encoded (Content-Encoding)")
            finish()
        }
    }
    // CORS is installed only when a cross-origin caller actually exists (an explicit allow-list
    // via CORS_ALLOWED_HOSTS). Production is single-origin (Ktor serves the SPA) and local dev
    // goes through the Vite proxy, so the default is: no CORS plugin, browsers enforce
    // same-origin, and no Access-Control-* headers are emitted.
    val corsHosts = environment.config.propertyOrNull("http.corsHosts")?.getString()
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        .orEmpty()
    if (corsHosts.isNotEmpty()) {
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(EXPECTED_REVISION_HEADER)
            corsHosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
        }
    }
    // Behind a TLS-terminating reverse proxy / ingress, trust X-Forwarded-* so the client IP
    // (rate-limit buckets) and scheme (HTTPS redirect) are the real client's, not the proxy's.
    // Off by default: honoring these headers from direct clients would let them spoof both.
    if (environment.config.propertyOrNull("http.behindProxy")?.getString()?.toBoolean() == true) {
        val proxyHops = requireConfigInt(environment.config, "http.proxyHops", min = 1)
        // HAProxy's `option forwardedfor` APPENDS a fresh X-Forwarded-For header LINE instead of
        // merging into a line the client already sent, so the request can carry the client's own
        // (spoofed) line first and the proxy's real one second. XForwardedHeaders resolves the
        // for-header via Headers.get(name) — the FIRST header LINE only — so it would key the
        // rate limiter on the spoofable client line. Fold every X-Forwarded-For line ourselves
        // (RFC 2616: multiple header fields sharing a name may be combined by joining their
        // values with a comma, in the order received) and resolve the trusted hop directly,
        // BEFORE XForwardedHeaders reads anything: the Setup phase always runs before its
        // Plugins-phase onCall (RawForwardedForLinesTest pins the ordering against a real
        // Netty engine — ktor-client's own request writer folds repeated header() calls into
        // one wire line before send, so a plain testApplication client cannot reproduce it).
        intercept(ApplicationCallPipeline.Setup) {
            resolveForwardedForOrigin(call, proxyHops)
        }
        install(XForwardedHeaders) {
            // Only the headers the proxy contract sets — and therefore overwrites. Ktor's defaults
            // also honour X-Forwarded-Server / X-Forwarded-Protocol / X-Forwarded-SSL /
            // Front-End-Https, which a proxy that sets just the canonical ones passes through
            // from the client untouched (Lettuce's v3.6.2 finding; ForwardedHeadersTest).
            hostHeaders.clear()
            hostHeaders.add(HttpHeaders.XForwardedHost)
            protoHeaders.clear()
            protoHeaders.add(HttpHeaders.XForwardedProto)
            httpsFlagHeaders.clear()
            // Untrusted too: a client-supplied non-numeric value throws a NumberFormatException
            // inside Ktor's own handler (a 500 raised from CallSetup, before any route runs), and
            // nothing reads it — the scheme-derived default port set via protoHeaders above is
            // all the HTTPS redirect needs.
            portHeaders.clear()
            // X-Forwarded-For is resolved by resolveForwardedForOrigin above (multi-line fold);
            // leave this empty so XForwardedHeaders never overwrites that result with its own
            // first-line-only, unfolded read.
            forHeaders.clear()
        }
    }
    // Responses only. The plugin's default mode also INFLATES request bodies (`Content-Encoding`, `maxDecodedContentLength` unlimited),
    // after `RequestBodyLimit` has counted the wire bytes: an 8 KiB gzip body could inflate past the heap (checkup C-SEC-1). A request with
    // a `Content-Encoding` is refused with a 415 above, and this keeps the plugin from decoding one even if that guard were lost.
    install(Compression) {
        mode = CompressionConfig.Mode.CompressResponse
        default()
        // The bulk ADR export (0.8.0) is a ZIP, already deflated entry by entry: compressing it again costs CPU for nothing.
        excludeContentType(ContentType.Application.Zip)
    }
    // DefaultHeaders' own `Server: Ktor/<version>` would disclose the framework and its exact version to
    // every client (checkup 2C18); a fixed generic value replaces it. `Date` stays.
    install(DefaultHeaders) {
        header(HttpHeaders.Server, "hammurabi")
    }
    if (!developmentMode) {
        install(HSTS) {
            includeSubDomains = true
        }
        install(HttpsRedirect) {
            // The port to redirect to. By default 443, the default HTTPS port.
            sslPort = 443
            // 301 Moved Permanently, or 302 Found redirect.
            permanentRedirect = true
        }
    }
}

/**
 * The key a client is known by (see `configureHttp`): the connection's NUMERIC remote address. The origin's `remoteHost` otherwise defaults
 * to the engine's, a reverse-DNS name. Internal for the unit test, which cannot make a test host resolve one.
 */
internal fun numericClientHost(local: RequestConnectionPoint): String = local.remoteAddress

/** Whether a request's `Content-Encoding` header lines ask for anything but `identity` (a blank value is none: nothing was encoded). */
internal fun hasEncodedBody(lines: List<String>?): Boolean =
    lines.orEmpty().flatMap { it.split(',') }.map { it.trim() }.any { it.isNotEmpty() && !it.equals("identity", ignoreCase = true) }

/**
 * The proxy-trust counterpart to XForwardedHeaders' built-in for-header handling — the identical
 * trust-from-the-end selection (`http.proxyHops`; ForwardedHeadersTest), but folding EVERY
 * X-Forwarded-For header LINE first (`getAll`, not `get`), so a line a trusted proxy APPENDS
 * rather than merges is not shadowed by a client-supplied line of the same name. A hop count in
 * excess of what the request actually carries falls back to the last available value — same
 * fallback Ktor's own useLastProxy()/skipLastProxies() apply — never an exception. Writes
 * directly into the call's MutableOriginConnectionPoint (the same attribute XForwardedHeaders
 * itself populates), so RateLimits' `call.request.origin.remoteHost` sees the resolved value
 * whether or not X-Forwarded-For is present at all.
 */
private fun resolveForwardedForOrigin(call: ApplicationCall, proxyHops: Int) {
    val hops = call.request.headers.getAll(HttpHeaders.XForwardedFor)
        ?.flatMap { it.split(',') }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?: return
    val chosen = hops.getOrNull(hops.size - proxyHops) ?: hops.last()
    val origin = call.mutableOriginConnectionPoint
    origin.remoteHost = chosen
    // Ktor's own resolution (io.ktor.server.plugins.forwardedheaders.isNotHostAddress, internal)
    // also sets remoteAddress only for a value that looks like an IP rather than a hostname — no
    // letters, or an IPv6 literal (which always contains ':'); reimplemented here since the
    // original is not visible outside its module.
    if (chosen.contains(':') || chosen.none { it.isLetter() }) origin.remoteAddress = chosen
}
