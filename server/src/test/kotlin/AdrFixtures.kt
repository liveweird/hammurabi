package ch.nokillswit

import ch.nokillswit.adrs.AdrCreateRequest
import ch.nokillswit.adrs.AdrEventPageResponse
import ch.nokillswit.adrs.AdrEventResponse
import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrParticipantsRequest
import ch.nokillswit.adrs.AdrResponse
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrService
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.AdrTransitionRequest
import ch.nokillswit.adrs.AdrUpdateRequest
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID
import kotlin.test.assertEquals

/** One seeded user and the client signed in as them. */
class AdrActor(val id: UInt, val client: HttpClient)

/** Seeds a unique user (named after [prefix]) and signs them in. */
suspend fun ApplicationTestBuilder.adrActor(prefix: String, role: UserRole = UserRole.USER): AdrActor {
    val email = uniqueEmail(prefix)
    val id = TestUsers.seed(email = email, password = "pw", name = prefix.replaceFirstChar { it.uppercase() }, role = role)
    return AdrActor(id, authedClient(email, "pw"))
}

/**
 * The people of one ADR: its Driver (who creates it), the Approver, a Contributor, an Informed, a stranger who holds no role, and an
 * ADMIN. Everyone is a fresh user, so the cast never collides with another test's.
 */
class AdrCast(
    val admin: AdrActor,
    val driver: AdrActor,
    val approver: AdrActor,
    val contributor: AdrActor,
    val informed: AdrActor,
    val stranger: AdrActor,
) {
    val everyone: Map<String, AdrActor>
        get() = mapOf(
            "admin" to admin, "driver" to driver, "approver" to approver,
            "contributor" to contributor, "informed" to informed, "stranger" to stranger,
        )

    /** A fresh ADR raised by the Driver with the other three roles attached. Pre-review content is complete unless [complete] is false. */
    suspend fun raise(complete: Boolean = true): AdrResponse = driver.client.createAdr(
        complete = complete,
        participants = listOf(
            AdrParticipantRequest(approver.id, AdrRole.APPROVER),
            AdrParticipantRequest(contributor.id, AdrRole.CONTRIBUTOR),
            AdrParticipantRequest(informed.id, AdrRole.INFORMED),
        ),
    )
}

suspend fun ApplicationTestBuilder.adrCast(): AdrCast = AdrCast(
    admin = adrActor("adm", UserRole.ADMIN),
    driver = adrActor("drv"),
    approver = adrActor("apr"),
    contributor = adrActor("con"),
    informed = adrActor("inf"),
    stranger = adrActor("str"),
)

/** A name no other test uses, so the active-name uniqueness never collides across tests. */
fun adrName(prefix: String = "ADR") = "$prefix ${UUID.randomUUID()}"

/** POSTs an ADR (asserting 201) and returns it; [complete] fills the context, decision and consequences `IN_REVIEW` needs. */
suspend fun HttpClient.createAdr(
    name: String? = adrName(),
    problem: String = "How do we store the decisions?",
    complete: Boolean = false,
    participants: List<AdrParticipantRequest> = emptyList(),
    expiresOn: String? = null,
): AdrResponse {
    val response = postJson(
        "/api/v1/adrs",
        AdrCreateRequest(
            name = name,
            problem = problem,
            context = if (complete) "The context" else "",
            decision = if (complete) "The decision" else "",
            consequences = if (complete) "The consequences" else "",
            expiresOn = expiresOn,
            participants = participants,
        ),
    )
    assertEquals(HttpStatusCode.Created, response.status, "create: ${response.status}")
    return response.body()
}

suspend fun HttpClient.getAdr(id: UInt): AdrResponse = get("/api/v1/adrs/$id").body()

/** The whole document of [this] as an update request: the starting point of an edit (change what the test is about). */
fun AdrResponse.asUpdate(): AdrUpdateRequest = AdrUpdateRequest(
    name = name,
    problem = problem,
    context = context,
    decision = decision,
    alternatives = alternatives,
    consequences = consequences,
    expiresOn = expiresOn,
)

suspend fun HttpClient.saveAdr(id: UInt, revision: Long, request: AdrUpdateRequest): HttpResponse =
    put("/api/v1/adrs/$id") {
        header(EXPECTED_REVISION_HEADER, revision.toString())
        contentType(ContentType.Application.Json)
        setBody(request)
    }

suspend fun HttpClient.moveAdr(id: UInt, revision: Long, to: AdrStatus): HttpResponse =
    post("/api/v1/adrs/$id/transitions") {
        header(EXPECTED_REVISION_HEADER, revision.toString())
        contentType(ContentType.Application.Json)
        setBody(AdrTransitionRequest(to))
    }

/** Moves the ADR to [to] with its CURRENT revision, asserting 200. */
suspend fun HttpClient.moveAdrNow(id: UInt, to: AdrStatus): AdrResponse {
    val response = moveAdr(id, getAdr(id).revision, to)
    assertEquals(HttpStatusCode.OK, response.status, "move to $to: ${response.status}")
    return response.body()
}

/** Replaces the participants under [revision] (default: the ADR's current one, 1 for an ADR that cannot be read). */
suspend fun HttpClient.setParticipants(id: UInt, participants: List<AdrParticipantRequest>, revision: Long? = null): HttpResponse {
    val expected = revision ?: get("/api/v1/adrs/$id").let { if (it.status == HttpStatusCode.OK) it.body<AdrResponse>().revision else 1L }
    return put("/api/v1/adrs/$id/participants") {
        header(EXPECTED_REVISION_HEADER, expected.toString())
        contentType(ContentType.Application.Json)
        setBody(AdrParticipantsRequest(participants))
    }
}

suspend fun HttpClient.adrEvents(id: UInt, query: String = ""): AdrEventPageResponse =
    get("/api/v1/adrs/$id/events$query").body()

/** The events of the ADR, oldest first (the history in the order it happened). */
suspend fun HttpClient.adrEventsOldestFirst(id: UInt): List<AdrEventResponse> =
    adrEvents(id, "?sort=createdAt,id&pageSize=100").items

/** Forces the stored status of [id] (no event, no revision bump): the shortcut into a state a test needs to start from. */
suspend fun forceAdrStatus(id: UInt, status: AdrStatus) {
    suspendTransaction(sharedDatabaseForTests()) {
        AdrService.Adrs.update({ AdrService.Adrs.id eq id }) { it[AdrService.Adrs.status] = status.name }
    }
}

/** Moves the ADR to a number of at least [floor] + 1 that no other ADR holds (the counter then continues above it); returns it. */
suspend fun forceAdrNumberAbove(id: UInt, floor: Int = 10_000): Int = suspendTransaction(sharedDatabaseForTests()) {
    val highest = AdrService.Adrs.number.max()
    val current = AdrService.Adrs.select(highest).toList().single()[highest] ?: 0
    val number = maxOf(floor, current) + 1
    AdrService.Adrs.update({ AdrService.Adrs.id eq id }) { it[AdrService.Adrs.number] = number }
    number
}

/** Forces the stored expiry date of [id] (the API refuses a date in the past). */
suspend fun forceAdrExpiry(id: UInt, expiresOn: String?) {
    suspendTransaction(sharedDatabaseForTests()) {
        AdrService.Adrs.update({ AdrService.Adrs.id eq id }) { it[AdrService.Adrs.expiresOn] = expiresOn }
    }
}

/** The stored status of [id], read straight from the table (a soft-deleted ADR included). */
suspend fun rawAdrStatus(id: UInt): String = suspendTransaction(sharedDatabaseForTests()) {
    AdrService.Adrs.select(AdrService.Adrs.status).where { AdrService.Adrs.id eq id }.toList().single()[AdrService.Adrs.status]
}
