package ch.nokillswit.infra.json

import java.security.MessageDigest

/** The lowercase hex SHA-256 digest of [text] (UTF-8): what a stored token is looked up by, never the token itself. */
fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
