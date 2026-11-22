package ch.nokillswit.adrs

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.paging.optionalEnum
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing

/** The caller's bounded personal ADR workspace. Like the ADR list, the only guard is authentication. */
fun Application.configureAdrMyWorkRoutes() {
    val service = attributes[AdrMyWorkServiceKey]
    routing {
        authenticate {
            get<AdrsRoute.MyWork> {
                val role = call.request.queryParameters.optionalEnum<AdrRole>("participantRole")
                call.respond(HttpStatusCode.OK, service.myWork(call.caller().userId, role))
            }
        }
    }
}
