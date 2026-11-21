package ch.nokillswit.auth

import java.security.SecureRandom
import java.util.Base64

/**
 * A real cost-12 hash of a throwaway string, verified against when a login's email matches no
 * account: the unknown-email branch must pay the same bcrypt price as the wrong-password one,
 * or the fast 401 becomes a timing oracle distinguishing unknown from existing accounts.
 * The verify result is always discarded — this hash can never authenticate anyone.
 */
internal const val TIMING_EQUALIZER_HASH = "\$2a\$12\$x7ALsPyiaDt.5FQcrfQSlOmsT7xycnGCanTeSTKCFqcBWW5K43GO6"

private val secureRandom = SecureRandom()

/** The 6-digit email-MFA code (leading zeros kept) — guess-resistance comes from the challenge
 *  attempt cap, not the code length (see auth/MfaChallenges.kt). */
internal fun generateMfaCode(): String = "%06d".format(secureRandom.nextInt(1_000_000))

private const val OPAQUE_TOKEN_BYTES = 32

/**
 * An opaque bearer secret: 32 `SecureRandom` bytes (256 bits) as unpadded base64url, always 43 characters of
 * `[A-Za-z0-9_-]`. The password-reset link token (and any future opaque bearer secret) is minted here; only the SHA-256
 * digest ([ch.nokillswit.infra.json.sha256Hex]) of either is ever stored.
 */
internal fun generateOpaqueToken(): String {
    val bytes = ByteArray(OPAQUE_TOKEN_BYTES).also(secureRandom::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
