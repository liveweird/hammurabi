package ch.nokillswit

import ch.nokillswit.infra.json.sha256Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** `infra/json/Sha256.kt`: the digest a password-reset grant is stored and looked up by. */
class Sha256Test {

    @Test
    fun `sha256Hex is the lowercase hex of the UTF-8 digest`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertEquals(64, sha256Hex("Zoë Żółć 🚀").length)
    }

    @Test
    fun `sha256Hex is stable for equal text and differs for different text`() {
        assertEquals(sha256Hex("token"), sha256Hex("token"))
        assertNotEquals(sha256Hex("token"), sha256Hex("token "))
    }
}
