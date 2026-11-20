package ch.nokillswit

import ch.nokillswit.infra.addressKey
import ch.nokillswit.infra.clientAddressKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The per-IP key (`infra/ClientAddress.kt`) as a pure function. Under `testApplication` the peer is the host name `localhost`, so the
 * branches where the peer IS an address cannot be reached through a request; the four combinations are pinned here instead. What the
 * buckets do with the key end to end: `ForwardedHeadersTest`; the pending share: `LivePendingTest`.
 */
class ClientAddressKeyTest {
    @Test
    fun `the origin host wins when it is an address, whatever the peer is`() {
        assertEquals("203.0.113.7", clientAddressKey("203.0.113.7", "10.0.0.1"), "address, address")
        assertEquals("203.0.113.7", clientAddressKey("203.0.113.7", "localhost"), "address, no address")
    }

    @Test
    fun `an origin host that is no address counts under the peer`() {
        assertEquals("10.0.0.1", clientAddressKey("junk", "10.0.0.1"), "no address, address")
        assertEquals("v6/20010db800010002", clientAddressKey("junk", "2001:db8:1:2::9"), "the peer is folded the same way")
        assertEquals("localhost", clientAddressKey("junk", " LocalHost "), "no address, no address: the peer's own cut text")
        assertEquals(64, clientAddressKey("junk", "p".repeat(500)).length)
    }

    @Test
    fun `an IPv4 address may carry a port, an IPv6 one may be bracketed with one`() {
        assertEquals("203.0.113.7", addressKey("203.0.113.7:51234"))
        assertEquals("203.0.113.7", addressKey(" 203.0.113.7:1 "))
        assertEquals(addressKey("2001:db8:1:2::1"), addressKey("[2001:db8:1:2::1]:443"))
        assertNull(addressKey("203.0.113.7:"), "an empty port is no address")
        assertNull(addressKey("203.0.113.7:123456"), "six digits are no port")
        assertNull(addressKey("203.0.113.7:80:80"))
        assertNull(addressKey("999.0.113.7:80"))
    }
}
