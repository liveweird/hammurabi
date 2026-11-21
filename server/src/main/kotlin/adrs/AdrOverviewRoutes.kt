package ch.nokillswit.adrs

import ch.nokillswit.authz.caller
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing

/**
 * `GET /api/v1/adrs/overview` (0.9.0, `adr-model.md` "Overview"): the decision log, a read open to every authenticated user like the ADRs
 * themselves (nothing to disclose that the list does not show; no audit line, reads are not audited). The guard is `authenticate {}` alone
 * and there is no per-user rate bucket, like `GET /adrs`: the answer is capped in every section, built from a constant number of
 * indexed statements in one transaction, and a page load is one call. The service owns "today" (an injected clock, the UTC date the
 * expiry sweep judges by) and the `adrs.overview.expiringSoonDays` horizon, which the composition root boot-validates.
 */
fun Application.configureAdrOverviewRoutes() {
    val service = attributes[AdrOverviewServiceKey]
    routing {
        authenticate {
            get<AdrsRoute.Overview> {
                call.respond(HttpStatusCode.OK, service.overview(call.caller().userId))
            }
        }
    }
}
