package ch.nokillswit

import ch.nokillswit.adrs.AdrEventType
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.infra.db.FieldChange
import ch.nokillswit.infra.db.MAX_CHANGE_VALUE_LENGTH
import ch.nokillswit.infra.db.encodeChanges
import ch.nokillswit.infra.db.nameOnlyChange
import ch.nokillswit.infra.db.scalarChange
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /api/v1/adrs/{id}/events` (V11): one structural event per mutation with its params, the actor, newest first, paged and sortable.
 * The participants' events are in `AdrParticipantsTest`, the system's `EXPIRED` in `AdrExpirySweepTest`.
 */
class AdrHistoryTest {

    @Test
    fun `creating records one CREATED event with the key and the name, by the Driver`() = testApplication {
        usePostgresTestcontainer()
        val driver = adrActor("histdrv")
        val adr = driver.client.createAdr(name = adrName("Created"))
        val event = driver.client.adrEvents(adr.id).items.single()
        assertEquals(AdrEventType.CREATED, event.type)
        assertEquals(mapOf("key" to adr.key, "name" to adr.name), event.params)
        assertEquals(driver.id, event.userId)
        assertEquals("Histdrv", event.userName)
        assertFalse(event.userDeleted)
        assertTrue(event.createdAt >= adr.createdAt)
    }

    @Test
    fun `an edit records ONE UPDATED event - short fields with their sides, Markdown by name only`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise()
        val update = adr.asUpdate().copy(
            name = adrName("Renamed"),
            context = "Sensitive markdown that must not be copied into the history",
            consequences = "More text",
            expiresOn = "2099-05-06",
        )
        // The Contributor may not set the expiry: the refused edit recorded nothing.
        assertEquals(HttpStatusCode.Forbidden, cast.contributor.client.saveAdr(adr.id, adr.revision, update).status)
        assertEquals(0, cast.driver.client.adrEvents(adr.id).items.count { it.type == AdrEventType.UPDATED })
        assertEquals(HttpStatusCode.NoContent, cast.driver.client.saveAdr(adr.id, adr.revision, update).status)
        val updated = cast.driver.client.adrEvents(adr.id).items.single { it.type == AdrEventType.UPDATED }
        assertEquals("name,context,consequences,expiresOn", updated.params["changed"])
        assertEquals(adr.name, updated.params["name.from"])
        assertEquals(update.name, updated.params["name.to"])
        assertEquals(null, updated.params["expiresOn.from"])
        assertEquals("2099-05-06", updated.params["expiresOn.to"])
        assertFalse(updated.params.keys.any { it.startsWith("context.") || it.startsWith("consequences.") }, "free text is never recorded")
        assertFalse(updated.params.values.any { "Sensitive" in it })
        assertEquals(cast.driver.id, updated.userId)
        // The next save changes only the date: one more event, only that field.
        val after = cast.driver.client.getAdr(adr.id)
        cast.driver.client.saveAdr(adr.id, after.revision, after.asUpdate().copy(expiresOn = "2099-07-08"))
        val second = cast.driver.client.adrEvents(adr.id).items.first()
        assertEquals(mapOf("changed" to "expiresOn", "expiresOn.from" to "2099-05-06", "expiresOn.to" to "2099-07-08"), second.params)
    }

    @Test
    fun `the field diff - equal is no change, free text is named only, a long short value degrades to its name`() {
        assertEquals(null, scalarChange("name", "same", "same"))
        assertEquals(FieldChange("name", "a", "b"), scalarChange("name", "a", "b"))
        assertEquals(FieldChange("expiresOn", null, "2099-01-01"), scalarChange("expiresOn", null, "2099-01-01"))
        assertEquals(FieldChange("name"), scalarChange("name", "a", "x".repeat(MAX_CHANGE_VALUE_LENGTH + 1)))
        val atTheCeiling = "x".repeat(MAX_CHANGE_VALUE_LENGTH)
        assertEquals(FieldChange("name", atTheCeiling, "y"), scalarChange("name", atTheCeiling, "y"))
        assertEquals(null, nameOnlyChange("context", "same", "same"))
        assertEquals(FieldChange("context"), nameOnlyChange("context", "old", "new"))
        assertEquals(emptyMap(), encodeChanges(emptyList()))
        assertEquals(
            mapOf("changed" to "name,context", "name.from" to "a", "name.to" to "b"),
            encodeChanges(listOf(FieldChange("name", "a", "b"), FieldChange("context"))),
        )
    }

    @Test
    fun `the history is newest first by default, sortable and paged, and readable by anyone signed in`() = testApplication {
        usePostgresTestcontainer()
        val cast = adrCast()
        val adr = cast.raise(complete = true)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.SOLUTION_DRAFT)
        cast.driver.client.moveAdrNow(adr.id, AdrStatus.IN_REVIEW)
        val newest = cast.stranger.client.adrEvents(adr.id)
        assertEquals(6, newest.total) // CREATED, the 3 initial participants, the 2 moves
        assertEquals(AdrEventType.TRANSITIONED, newest.items.first().type)
        assertEquals(mapOf("from" to "SOLUTION_DRAFT", "to" to "IN_REVIEW"), newest.items.first().params)
        val oldest = cast.stranger.client.adrEvents(adr.id, "?sort=createdAt,id")
        assertEquals(AdrEventType.CREATED, oldest.items.first().type)
        assertEquals(newest.items.reversed(), oldest.items)
        val firstPage = cast.stranger.client.adrEvents(adr.id, "?pageSize=2&page=1")
        val thirdPage = cast.stranger.client.adrEvents(adr.id, "?pageSize=2&page=3")
        assertEquals(2, firstPage.items.size)
        assertEquals(newest.total, firstPage.total)
        assertEquals(oldest.items.take(2).reversed(), thirdPage.items)
        for (bad in listOf("sort=params", "sort=createdAt,createdAt", "pageSize=101")) {
            assertEquals(HttpStatusCode.BadRequest, cast.stranger.client.get("/api/v1/adrs/${adr.id}/events?$bad").status, bad)
        }
    }

    @Test
    fun `an actor deleted since is flagged and keeps the recorded name`() = testApplication {
        usePostgresTestcontainer()
        val driver = adrActor("histgone")
        val adr = driver.client.createAdr()
        val reader = adrActor("reader")
        TestUsers.softDelete(driver.id)
        val event = reader.client.adrEvents(adr.id).items.single()
        assertTrue(event.userDeleted)
        assertEquals("Histgone", event.userName)
    }
}
