package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey

/**
 * The ONE per-account failure counter, shared by every route that verifies a credential for an account: `/login` (the
 * password), `/login/mfa` (the emailed code) and the self password change (`currentPassword`). N wrong guesses across all
 * three lock the account exactly as N failed logins would. Published as an attribute (not built inside `configureAuthRoutes`)
 * because `configureUserRoutes` registers earlier in `application.yaml` than the auth routes and reads it per request.
 */
val LoginThrottleKey = AttributeKey<LoginThrottle>("LoginThrottle")

/** Module `configureLoginThrottle` (application.yaml, before the feature routes): builds the throttle from `security.lockout.*`. */
fun Application.configureLoginThrottle() {
    // Boot-validated through the shared requireConfigInt/requireConfigLong: a malformed or out-of-range value is a config error,
    // e.g. LOGIN_LOCKOUT_THRESHOLD=0 would lock out every account on its first attempt.
    attributes.put(
        LoginThrottleKey,
        LoginThrottle(
            threshold = requireConfigInt(environment.config, "security.lockout.threshold", min = 1),
            // Bounded above (30 days) as well as below: an unbounded value survives the *1000 millis conversion right up until it
            // doesn't - a durationSeconds near Long.MAX_VALUE overflows into a negative millis figure, which would silently
            // DISABLE the lockout instead of refusing to start.
            lockoutMillis = requireConfigLong(
                environment.config, "security.lockout.durationSeconds", min = 1, max = MAX_DURATION_SECONDS,
            ) * 1000,
            maxTracked = requireConfigInt(environment.config, "security.lockout.maxTracked", min = 1, max = MAX_TRACKED_IDENTITIES),
            // The progressive lock for MFA-tripped locks only (LoginThrottle): the same 30-day ceiling, for the same overflow reason.
            mfaMaxLockoutMillis = requireConfigLong(
                environment.config, "security.lockout.mfaMaxDurationSeconds", min = 1, max = MAX_DURATION_SECONDS,
            ) * 1000,
            mfaDecayMillis = requireConfigLong(
                environment.config, "security.lockout.mfaDecaySeconds", min = 1, max = MAX_DURATION_SECONDS,
            ) * 1000,
        ),
    )
}

/** The 429 every locked-account refusal answers (login, MFA step, password change), so the detail never forks. */
internal fun accountLockedException() =
    TooManyRequestsException("Too many failed login attempts for this account — try again later")

/** The 429 a full lockout store answers for an identity it does not track yet. */
internal fun lockoutCapacityException() =
    TooManyRequestsException("Too many sign-in identities are being tracked — try again later")

/**
 * One credential check of one account against the shared [LoginThrottle]: what a route does around its password / code
 * verification. The three callers (`/login`, `/login/mfa`, the self password change) differ only in their audit identity, the
 * event for a refused locked account, whether the account is [knownAccount] and what a lock must also do ([onLocked], e.g. drop
 * the account's pending MFA challenges). Every refusal is a THROWN 429, never `respondProblem`: StatusPages' generic 429
 * handler would otherwise replace the specific detail.
 *
 * [knownAccount] is true where the route has ALREADY proven the account exists before the credential is compared (the MFA
 * challenge's subject, the signed-in caller): its failures are counted even at a full store (the junk identities that fill a
 * store must not buy free guesses), and the capacity refusal never applies. `/login` passes false: its one existence probe
 * (`UserService.accountExists`) only chooses the audit identity and is not trusted for the throttle, and refusing an untracked
 * identity BEFORE any password work (the same 429, after the same one statement, for a known and an unknown email) is what keeps
 * the capacity answer free of an existence signal.
 */
internal class AccountGuess(
    private val throttle: LoginThrottle,
    private val email: String,
    private val auditFields: List<Pair<String, Any?>>,
    private val lockedEvent: String = EVENT_LOGIN_REJECTED_LOCKED,
    /** The fields of [lockedEvent], when it is not a `login.*` event and so follows its own naming (`observability.md` "Field-naming"). */
    private val lockedFields: List<Pair<String, Any?>> = auditFields,
    private val knownAccount: Boolean = false,
    /** True where a failure is a wrong emailed code (the MFA step): a lock it trips is progressive, see [LoginThrottle]. */
    private val mfaCode: Boolean = false,
    private val onLocked: () -> Unit = {},
) {
    private fun audited(event: String) = audit(event, *auditFields.toTypedArray())

    private fun auditedLocked() = audit(lockedEvent, *lockedFields.toTypedArray())

    /** BEFORE any password work: a locked identity (and, unless [knownAccount], an untracked one at a full store) is refused. */
    fun requireNotLocked() {
        when (throttle.preflight(email, knownAccount)) {
            LoginThrottle.Preflight.ALLOWED -> Unit
            LoginThrottle.Preflight.LOCKED -> {
                auditedLocked()
                throw accountLockedException()
            }
            LoginThrottle.Preflight.CAPACITY_EXCEEDED -> {
                audited(EVENT_LOGIN_CAPACITY_REJECTED)
                throw lockoutCapacityException()
            }
        }
    }

    /**
     * Counts one wrong guess and answers what `/login` answers to a failure: nothing on a plain or a locking failure (the caller
     * then sends its own failure; the locking one also writes `login.lockout`), a 429 on an already-active lock (the race the
     * preflight cannot see) or, for an unknown identity only, a full store.
     */
    fun recordFailure() {
        val outcome = throttle.recordFailureOutcome(email, knownAccount, mfaCode)
        when (outcome.result) {
            LoginThrottle.FailureResult.CAPACITY_EXCEEDED -> {
                audited(EVENT_LOGIN_CAPACITY_REJECTED)
                throw lockoutCapacityException()
            }
            LoginThrottle.FailureResult.ALREADY_LOCKED -> {
                onLocked()
                auditedLocked()
                throw accountLockedException()
            }
            LoginThrottle.FailureResult.LOCKED_NOW -> {
                onLocked()
                // The length and level are audit-only: the 429 stays the one uniform answer, no factor is disclosed.
                audit(
                    EVENT_LOGIN_LOCKOUT, *auditFields.toTypedArray(),
                    "lockSeconds" to outcome.lockMillis / 1000, "escalationLevel" to outcome.level,
                )
            }
            LoginThrottle.FailureResult.RECORDED -> Unit
        }
    }

    /** A verified credential clears the counter, unless a lock tripped since the preflight: that still refuses, as at login. */
    fun recordSuccess() {
        if (throttle.recordSuccess(email) == LoginThrottle.SuccessResult.LOCKED) {
            onLocked()
            auditedLocked()
            throw accountLockedException()
        }
    }
}
