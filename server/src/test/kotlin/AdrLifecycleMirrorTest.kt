package ch.nokillswit

import ch.nokillswit.adrs.ALLOWED_TRANSITIONS
import ch.nokillswit.adrs.AdrRole
import ch.nokillswit.adrs.AdrStanding
import ch.nokillswit.adrs.AdrStatus
import ch.nokillswit.adrs.SYSTEM_STATUSES
import ch.nokillswit.adrs.allowedTransitions
import ch.nokillswit.authz.CallerPrincipal
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SPA's lifecycle diagram describes the ADR machine in `web/src/utils/adrLifecycle.ts` (`ADR_LIFECYCLE_MOVES`: edge and actor).
 * That table is a hand-kept mirror, so this suite pins it to the server: its edges are exactly [ALLOWED_TRANSITIONS] (the automatic ones
 * exactly [SYSTEM_STATUSES]), and each move's actor is what the verdict [allowedTransitions] gives a Driver and an Approver who is not an
 * ADMIN. A plain source scan of the TS literal (one `{ from, to, actor }` per line): no Docker, no application boot.
 */
class AdrLifecycleMirrorTest {
    private val repoRoot = File(System.getProperty("repo.root") ?: "..")
    private val move = Regex("""\{ from: "([A-Z_]+)", to: "([A-Z_]+)", actor: "([A-Z_]+)" \}""")

    private data class Move(val from: AdrStatus, val to: AdrStatus, val actor: String)

    private fun spaMoves(): List<Move> {
        val file = File(repoRoot, "web/src/utils/adrLifecycle.ts")
        assertTrue(file.isFile, "${file.absolutePath} not found")
        return move.findAll(file.readText())
            .map { Move(AdrStatus.valueOf(it.groupValues[1]), AdrStatus.valueOf(it.groupValues[2]), it.groupValues[3]) }
            .toList()
    }

    private fun standing(role: AdrRole) = AdrStanding(CallerPrincipal(1u, "x@test", emptySet()), role)

    @Test
    fun `the diagram's moves are exactly the server's lifecycle map`() {
        val spa = spaMoves()
        assertTrue(spa.isNotEmpty(), "no move parsed: the literal's shape in adrLifecycle.ts changed")
        assertEquals(spa.size, spa.map { it.from to it.to }.toSet().size, "a move is listed twice in adrLifecycle.ts")
        val server = ALLOWED_TRANSITIONS.flatMap { (from, targets) -> targets.map { from to it } }.toSet()
        assertEquals(
            server,
            spa.map { it.from to it.to }.toSet(),
            "adrLifecycle.ts must list every edge of ALLOWED_TRANSITIONS and no other",
        )
    }

    @Test
    fun `the automatic moves are exactly the ones into the system's statuses`() {
        val automatic = spaMoves().filter { it.actor == "AUTOMATIC" }.map { it.to }.toSet()
        assertEquals(SYSTEM_STATUSES, automatic)
        val personMovesIntoSystemStatus = spaMoves().filter { it.to in SYSTEM_STATUSES && it.actor != "AUTOMATIC" }
        assertEquals(emptyList(), personMovesIntoSystemStatus, "a system status is never a person's move")
    }

    @Test
    fun `each move's actor is what the verdict gives a Driver and an Approver`() {
        val driver = standing(AdrRole.DRIVER)
        val approver = standing(AdrRole.APPROVER)
        for (m in spaMoves().filter { it.actor != "AUTOMATIC" }) {
            val byDriver = m.to in allowedTransitions(driver, m.from)
            val byApprover = m.to in allowedTransitions(approver, m.from)
            val expected = when {
                byDriver && byApprover -> "APPROVER_OR_DRIVER"
                byDriver -> "DRIVER"
                byApprover -> "APPROVER"
                else -> "NOBODY"
            }
            assertEquals(expected, m.actor, "the actor of ${m.from} to ${m.to} in adrLifecycle.ts differs from AdrAccess.kt")
        }
    }

    @Test
    fun `no other participant may take a move, and the SPA names no other actor`() {
        for (role in listOf(AdrRole.CONTRIBUTOR, AdrRole.INFORMED)) {
            for (from in AdrStatus.entries) {
                assertEquals(emptyList(), allowedTransitions(standing(role), from), "$role must have no move from $from")
            }
        }
        val known = setOf("DRIVER", "APPROVER", "APPROVER_OR_DRIVER", "AUTOMATIC")
        assertEquals(emptyList(), spaMoves().filter { it.actor !in known })
    }
}
