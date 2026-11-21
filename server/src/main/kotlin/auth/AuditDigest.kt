package ch.nokillswit.auth

import ch.nokillswit.plugins.JwtConfigKey
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * How an audit line names an address that has no account: a keyed digest, never the text. What is typed into the email box can be
 * anything, a password included, and an UNSALTED hash of a short or weak one is recoverable offline by trying candidates, so the digest
 * is an HMAC-SHA256 under a key only this deployment holds. Repeated attempts at one address still carry one digest (so an operator
 * can correlate a guessing run), and nothing outside the deployment can compute or test one.
 *
 * The key is DERIVED from the JWT secret, `HMAC-SHA256(jwtSecret, "hammurabi.audit-digest.v1")`: that secret is the one every mode
 * already requires (production refuses a missing or public one at startup), so the digest needs no new setting and no key that could be
 * forgotten. The fixed label makes the key a different value from the secret itself, which signs tokens, so a digest is never a token
 * signature and a leaked digest key does not sign anything. Rotating the JWT secret changes every digest, which only breaks
 * correlation across the rotation (the tokens are invalidated by it too). The in-memory lockout key (`loginIdentityKey`) is a separate,
 * unkeyed hash that is never logged.
 */
internal class AuditDigest(jwtSecret: String) {
    private val key: ByteArray = hmac(jwtSecret.toByteArray(Charsets.UTF_8), DERIVATION_LABEL.toByteArray(Charsets.UTF_8))

    /** The 64-character lowercase hex digest of [email], folded like the lockout key (trimmed, lower-cased). */
    fun of(email: String): String = hmac(key, email.trim().lowercase().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DERIVATION_LABEL = "hammurabi.audit-digest.v1"

        /** A fresh [Mac] per call: the class is not thread-safe, and the calls are rare (failed logins, reset requests). */
        fun hmac(key: ByteArray, message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
    }
}

private val AuditDigestKey = AttributeKey<AuditDigest>("AuditDigest")

/** The application's [AuditDigest], derived once from the JWT secret that `configureSecurity` published. */
internal fun Application.auditDigest(): AuditDigest =
    attributes.computeIfAbsent(AuditDigestKey) { AuditDigest(attributes[JwtConfigKey].secret) }
