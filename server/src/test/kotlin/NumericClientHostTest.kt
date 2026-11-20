package ch.nokillswit

import ch.nokillswit.plugins.numericClientHost
import io.ktor.http.RequestConnectionPoint
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `plugins/Http.kt` does with network-derived text before anything else reads it: the per-IP key is the NUMERIC remote address
 * (not the engine's reverse-DNS name).
 */
class NumericClientHostTest {

    /** A property bag standing in for a Ktor connection-point interface (getters read it, setters write it). */
    private inline fun <reified T : Any> pointOf(values: MutableMap<String, Any?>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when {
                method.name.startsWith("get") -> values[method.name.removePrefix("get")]
                method.name.startsWith("set") -> {
                    values[method.name.removePrefix("set")] = args[0]
                    null
                }
                else -> error("unexpected call ${method.name}")
            }
        } as T

    @Test
    fun `the origin's remote host becomes the numeric address even when the engine would resolve a name`() {
        // Netty's getRemoteHost() is InetSocketAddress.getHostName(): a reverse-DNS lookup. A client with PTR records for many
        // addresses would get one bucket key per address (and a /64 would not group). The Netty engine cannot be made to resolve a name
        // here, so the
        // connection point is played by hand; the function is what the Setup interceptor of configureHttp copies into the origin.
        val local = pointOf<RequestConnectionPoint>(
            mutableMapOf("RemoteHost" to "client-17.attacker.example", "RemoteAddress" to "203.0.113.17"),
        )
        assertEquals("client-17.attacker.example", local.remoteHost, "the engine's own remoteHost is the resolved name")
        assertEquals("203.0.113.17", numericClientHost(local))

        val v6 = pointOf<RequestConnectionPoint>(
            mutableMapOf("RemoteHost" to "v6.attacker.example", "RemoteAddress" to "2001:db8:1:2::9"),
        )
        assertEquals("2001:db8:1:2::9", numericClientHost(v6))
    }
}
