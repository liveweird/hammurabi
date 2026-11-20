package ch.nokillswit.infra

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.netty.util.NetUtil
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/** An IPv6 address is counted by its /64, the smallest block a provider hands to one subscriber: 8 of its 16 bytes. */
private const val IPV6_NETWORK_BYTES = 8
private const val IPV4_BYTES = 4

/** An IPv4-mapped IPv6 address (::ffff:a.b.c.d) is ten zero bytes, two 0xff bytes and the IPv4 address. */
private const val IPV4_MAPPED_ZEROS = 10
private const val IPV4_MAPPED_OFFSET = 12
private const val MAPPED_MARK: Byte = -1

/** The last-resort key (the peer's own text is not a literal either) is cut so a malformed one cannot grow a map entry. */
private const val MAX_ADDRESS_KEY = 64

/** At most one "the forwarded hop is no address" WARN per this long, with the number of calls it stands for. */
private const val FALLBACK_WARN_INTERVAL_MS = 10L * 60 * 1000

private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** A dotted quad with a port (`203.0.113.7:51234`), the form Azure Application Gateway and IIS ARR write into `X-Forwarded-For`. */
private val IPV4_WITH_PORT = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):\d{1,5}""")
private val IPV6_LITERAL = Regex("""[0-9a-fA-F:][0-9a-fA-F:.]*""")

private val log = LoggerFactory.getLogger("ch.nokillswit.infra.ClientAddress")

/**
 * The key a client address is counted under, ONE function for every per-IP bound (the per-IP `RateLimit` buckets in
 * `plugins/RateLimits.kt`): an IPv4 address whole (a `:port` suffix
 * dropped), an IPv6 one by its /64 (so a host that rotates through its /64 is still one client), an IPv4-mapped IPv6 address as the IPv4
 * one. Null when [text] is NOT an address literal (a hostname, junk, an empty string): such text must never become a key of its own, or
 * a client behind a proxy that passes `X-Forwarded-For` through could mint a fresh bucket per request ([clientAddressKey] falls back to
 * the peer).
 */
internal fun addressKey(text: String): String? {
    val trimmed = text.trim()
    val host = (IPV4_WITH_PORT.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed)
        .removePrefix("[").substringBefore("%").substringBefore("]")
    // Netty's parser reads a literal and nothing else. `InetAddress.getByName` would hand any string it cannot parse numerically (".a:b",
    // "a:b") to the DNS resolver, and this text is client-influenced behind a proxy.
    val literal = IPV4_LITERAL.matches(host) || (':' in host && IPV6_LITERAL.matches(host))
    val bytes = if (literal) NetUtil.createByteArrayFromIpAddressString(host) else null
    return when {
        bytes == null -> null
        bytes.size == IPV4_BYTES -> ipv4(bytes, 0)
        // ::ffff:a.b.c.d is the IPv4 address a.b.c.d
        (0 until IPV4_MAPPED_ZEROS).all { bytes[it].toInt() == 0 } && bytes[10] == MAPPED_MARK && bytes[11] == MAPPED_MARK ->
            ipv4(bytes, IPV4_MAPPED_OFFSET)
        else -> "v6/" + bytes.take(IPV6_NETWORK_BYTES).joinToString("") { "%02x".format(it) }
    }
}

private fun ipv4(bytes: ByteArray, from: Int): String =
    (from until from + IPV4_BYTES).joinToString(".") { (bytes[it].toInt() and 0xff).toString() }

/**
 * The precedence of the per-IP key, pure: [addressKey] of [originHost] (the trusted `X-Forwarded-For` hop behind a proxy, the numeric
 * peer otherwise) when that is an address literal, else of [peer] (the TCP peer, never client text), so junk forwarded text counts
 * against the connection that sent it. A peer that is no literal either (never on a real socket) keys by its own cut, lower-cased text.
 */
internal fun clientAddressKey(originHost: String, peer: String): String =
    addressKey(originHost) ?: addressKey(peer) ?: peer.trim().lowercase().take(MAX_ADDRESS_KEY)

private val lastFallbackWarnMs = AtomicLong(0)
private val foldedFallbacks = AtomicLong(0)

/**
 * The per-IP key of this call ([clientAddressKey] of `origin.remoteHost` and `local.remoteAddress`). Behind a proxy the peer IS the proxy,
 * so a trusted hop that is no address (a header format the parser does not read) folds every such client into ONE bucket: a WARN says
 * so, at most once per ten minutes with the number of calls it stands for, and never carries the client's text.
 */
internal fun ApplicationCall.clientAddressKey(): String {
    val originHost = request.origin.remoteHost
    val peer = request.local.remoteAddress
    if (originHost != peer && addressKey(originHost) == null) warnFallback()
    return clientAddressKey(originHost, peer)
}

private fun warnFallback(now: Long = System.currentTimeMillis()) {
    val folded = foldedFallbacks.incrementAndGet()
    val last = lastFallbackWarnMs.get()
    if (now - last >= FALLBACK_WARN_INTERVAL_MS && lastFallbackWarnMs.compareAndSet(last, now)) {
        foldedFallbacks.addAndGet(-folded)
        log.warn(
            "the trusted X-Forwarded-For hop is not an IP address ({} call(s) in this window): such clients are counted under the " +
                "proxy's own address, so they share ONE per-IP bucket; check the proxy's header format and http.proxyHops",
            folded,
        )
    }
}
