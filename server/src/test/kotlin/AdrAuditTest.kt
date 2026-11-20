package ch.nokillswit

import ch.nokillswit.adrs.AdrParticipantRequest
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStatus
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The ADR audit events (`observability.md`): every mutation after its commit, only on a change; every denial is `authz.denied`. */
class AdrAuditTest {

    @Test
    fun `every mutation audits with ids and keys, never the content`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        withAuditCapture { capture ->
            val adr = cast.raise(complete = true)
            val id = adr.id.toLong()
            val created = capture.awaitEvent { it.message == "adr.created" && it.hasKeyValue("adrId", id) }
            assertNotNull(created, "create audits")
            assertTrue(created.hasKeyValue("byUserId", cast.driver.id.toLong()))
            assertTrue(created.hasKeyValue("number", adr.number))
            assertTrue(created.hasKeyValue("participants", 3))

            cast.driver.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(context = "A secret context", alternatives = "More"))
            val updated = capture.awaitEvent { it.message == "adr.updated" && it.hasKeyValue("adrId", id) }
            assertNotNull(updated, "an edit audits")
            assertTrue(updated.hasKeyValue("fieldsChanged", "context,alternatives"))
            assertTrue(updated.hasKeyValue("revision", 2L))
            val leaked = capture.events.any { e -> e.keyValuePairs.orEmpty().any { "secret" in it.value.toString() } }
            assertTrue(!leaked, "content never reaches the trail")

            cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
            val moved = capture.awaitEvent { it.message == "adr.transitioned" && it.hasKeyValue("adrId", id) }
            assertNotNull(moved, "a transition audits")
            assertTrue(moved.hasKeyValue("from", "PROBLEM_RAISED") && moved.hasKeyValue("to", "IN_REVIEW"))

            val handover = listOf(
                AdrParticipantRequest(cast.approver.id, AdrRole.DRIVER),
                AdrParticipantRequest(cast.driver.id, AdrRole.APPROVER),
                AdrParticipantRequest(adrActor("late").id, AdrRole.INFORMED),
            )
            assertEquals(HttpStatusCode.NoContent, cast.driver.client.setParticipants(adr.id, handover).status)
            val people = capture.awaitEvent { it.message == "adr.participants_changed" && it.hasKeyValue("adrId", id) }
            assertNotNull(people, "a participants change audits")
            assertTrue(people.hasKeyValue("changed", "${cast.driver.id}:DRIVER>APPROVER,${cast.approver.id}:APPROVER>DRIVER"))
            assertTrue(people.hasKeyValue("removed", "${cast.contributor.id}:CONTRIBUTOR,${cast.informed.id}:INFORMED"))

            assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("/api/v1/adrs/${adr.id}").status)
            val deleted = capture.awaitEvent { it.message == "adr.deleted" && it.hasKeyValue("adrId", id) }
            assertNotNull(deleted, "a delete audits")
            assertTrue(deleted.hasKeyValue("byUserId", cast.admin.id.toLong()))
        }
    }

    @Test
    fun `a denial is an authz denied event and a refused or empty write audits nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        withAuditCapture { capture ->
            val refused = cast.informed.client.saveAdr(adr.id, adr.revision, adr.asUpdate().copy(problem = "x"))
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            val denied = capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", "/api/v1/adrs/${adr.id}") }
            assertNotNull(denied, "the 403 leaves the denial trail")
            assertTrue(denied.hasKeyValue("byUserId", cast.informed.id.toLong()))

            // A stale revision, an illegal move and a no-op are not mutations.
            cast.driver.client.moveAdr(adr.id, 99, AdrStatus.CANCELLED)
            cast.driver.client.moveAdr(adr.id, adr.revision, AdrStatus.ACTIVE)
            cast.driver.client.saveAdr(adr.id, adr.revision, adr.asUpdate())
            delay(300)
            val adrEvents = capture.events.filter { it.message?.startsWith("adr.") == true && it.hasKeyValue("adrId", adr.id.toLong()) }
            assertEquals(emptyList(), adrEvents.map { it.message }.filter { it != "adr.created" })
        }
    }
}
