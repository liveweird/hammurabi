# ADR successors

- **Spec**: [tests/adr-successor.spec.ts](../tests/adr-successor.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; two throwaway regular users — a Driver (raises the
  ADRs through the API and links in the browser) and an Approver (activates)
- **Owns** (exclusive server-side state): its two throwaway users (unique `E2E Driver-*` / `E2E Approver-*` names), plus a third (`E2E Squatter-*`) in the detach scenario, and the ADRs (unique
  `e2e-adr-*` names) the Driver raises with the Approver named — all deleted through the API after each test, the ADRs first

## Scenario: a draft successor is linked to an ACTIVE ADR and activated, and the predecessor shows SUPERSEDED with the banner

1. The Driver raises a complete ADR with the Approver through the API; it goes to review (Driver) and is activated (Approver) through the API.
   The Driver raises a second, complete ADR (the successor), which stays a draft.
2. The Driver signs in and opens the successor. Its **Successor** section says "Replaces no ADR." and **Link** is disabled. They search the
   picker "This ADR supersedes…" by the predecessor's name, choose it and press **Link**.
   - *Expected*: the section shows a link "<key> <name>" to the predecessor and **Remove the link**; the History reads "Set to replace <key>".
3. They open the predecessor.
   - *Expected*: its status is still **Active** and a quiet note "A successor is on its way" shows.
4. Back on the successor they press **Send to review** and sign out.
   - *Expected*: the status reads **In review**.
5. The Approver signs in, opens the successor and presses **Activate**.
   - *Expected*: the successor is **Active**; its History reads "Activated and superseded <key>".
6. They open the predecessor.
   - *Expected*: the banner "This ADR is superseded" links to the successor; the status reads **Superseded** and "This ADR is closed: it is
     read-only."; the History reads "Superseded by <key>"; no tag editor is offered. The banner's link opens the successor, whose
     **Successor** section links back to the predecessor.

## Scenario: the predecessor's Driver detaches a successor somebody else parked on it, and both histories say so

1. The Driver raises a complete ADR with the Approver through the API and it goes active (Driver to review, Approver activates). A third,
   throwaway user (the squatter, who holds no role on it) raises a draft of their own and, through the API, links it to the active ADR.
2. The Driver signs in and opens the ACTIVE ADR. The cyan banner "A successor is on its way" names the squatter's draft and offers **Detach**.
   They press **Detach**, then **Keep it** in the confirmation.
   - *Expected*: the dialog closes and the banner is still there.
3. They press **Detach** again and confirm with **Detach**.
   - *Expected*: the banner is gone; the ADR is still **Active**; its History reads "Pending successor <key> detached".
4. They open the squatter's draft.
   - *Expected*: its **Successor** section reads "Replaces no ADR."; its History reads "No longer replaces <key>".

## Scenario: a link that would close a loop of successors is refused in words and changes nothing

1. The Driver raises two drafts through the API; through the API the second is set to replace the first.
2. The Driver signs in and opens the FIRST. Its **Successor** section reads "Set to replace this ADR" (the second). They pick the second in the
   picker and press **Link**.
   - *Expected*: an alert in the section says the link would close a loop of successors; the section still reads "Replaces no ADR.".

## Not covered here (and why)

- **Rival successors, terminal or deleted predecessors, the activation block when the predecessor is no longer active, the parallel-write
  locks** — pinned by `AdrSuccessorTest` (including the parallel cases) and `SuccessorGraphTest`.
- **The role matrix of the detach** (Driver, Approver and ADMIN of the predecessor only; a terminal predecessor; a racing activation) —
  `AdrSuccessorDetachTest`; the browser shows the button only to whoever the server's `canDetachSuccessor` allows (unit tests).
- **The role matrix** (who may link) — `AdrSuccessorTest` and `AdrAccessTest`; the browser shows the controls only to whoever the server's
  `canEditSuccessor` allows (unit tests).
