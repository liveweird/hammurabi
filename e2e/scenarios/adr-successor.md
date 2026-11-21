# ADR successors

- **Spec**: [tests/adr-successor.spec.ts](../tests/adr-successor.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; two throwaway regular users — a Driver (raises the
  ADRs through the API and links in the browser) and an Approver (accepts and activates)
- **Owns** (exclusive server-side state): its two throwaway users (unique `E2E Driver-*` / `E2E Approver-*` names), plus a third (`E2E Squatter-*`) in the detach scenario, two more (`E2E Other Driver-*` / `E2E Other Approver-*`) in the acceptance scenario (the withdrawal scenario creates an `E2E Other Driver-*` too), and the ADRs (unique
  `e2e-adr-*` names) the Driver raises with the Approver named — all deleted through the API after each test, the ADRs first

## Scenario: a draft successor is linked to an ACTIVE ADR and activated, and the predecessor shows SUPERSEDED with the banner

1. The Driver raises a complete ADR with the Approver through the API; it goes to review (Driver) and is activated (Approver) through the API.
   The Driver raises a second, complete ADR (the successor), which stays a draft.
2. The Driver signs in and opens the successor. Its **Successor** section says "Replaces no ADR." and **Link** is disabled. They search the
   picker "This ADR supersedes…" by the predecessor's name, choose it and press **Link**.
   - *Expected*: the section shows a link "<key> <name>" to the predecessor and **Remove the link**; the History reads "Set to replace <key>".
3. They open the predecessor.
   - *Expected*: its status is still **Active** and the banner "A successor is proposed" shows, with no **Accept** button for the Driver.
4. Back on the successor they press **Send to review** and sign out.
   - *Expected*: the status reads **In review**.
5. The Approver signs in, opens the predecessor and presses **Accept**, then **Accept** in the confirmation.
   - *Expected*: the banner now reads "A successor is on its way"; the History reads "Accepted <key> as its successor".
6. They open the successor and press **Activate**.
   - *Expected*: the successor is **Active**; its History reads "Activated and superseded <key>".
7. They open the predecessor.
   - *Expected*: the banner "This ADR is superseded" links to the successor; the status reads **Superseded** and "This ADR is closed: it is
     read-only."; the History reads "Superseded by <key>"; no tag editor is offered. The banner's link opens the successor, whose
     **Successor** section links back to the predecessor.

## Scenario: the predecessor's Driver detaches a successor somebody else parked on it, and both histories say so

1. The Driver raises a complete ADR with the Approver through the API and it goes active (Driver to review, Approver activates). A third,
   throwaway user (the squatter, who holds no role on it) raises a draft of their own and, through the API, links it to the active ADR.
2. The Driver signs in and opens the ACTIVE ADR. The grape banner "A successor is proposed" names the squatter's draft and offers **Detach**.
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

## Scenario: withdrawing the decision a draft was set to replace tells the draft's Driver and the withdrawn ADR stops listing it

1. The Driver raises a complete ADR with the Approver through the API and it goes active. A second, throwaway Driver (who holds no role on
   it) raises a draft of their own and, through the API, links it to the active ADR. The Approver then withdraws the active ADR (API).
2. The second Driver signs in.
   - *Expected*: the bell reads "Notifications (1 unseen)"; opening it shows "<old key>, which <draft key> was set to replace, is now
     Withdrawn".
3. They open the draft.
   - *Expected*: its History reads "<old key>, which this ADR was set to replace, is now Withdrawn"; its **Successor** section says the
     predecessor is no longer in force (Withdrawn) and the link is still there.
4. They open the withdrawn ADR.
   - *Expected*: its status is **Withdrawn**, there is no "A successor is proposed" banner, and its **Successor** section no longer reads
     "Set to replace this ADR" nor links to the draft.

## Scenario: the successor's Approver cannot activate over a decision in force until its Approver accepts the link

1. The Driver raises a complete ADR with the Approver and it goes active (through the API). A second team — another Driver and Approver, who
   hold no role on it — raises a complete ADR, links it to the active one (API) and sends it to review.
2. The other Approver signs in and opens their ADR.
   - *Expected*: the Status section says it is waiting for the decision's Approver to accept the ADR as its successor.
3. They press **Activate**.
   - *Expected*: an alert says the Approver of the decision it replaces has to accept it first; the ADR is still **In review**.
4. The decision's own Approver accepts the link through the API; the other Approver reloads and presses **Activate** again.
   - *Expected*: the ADR is **Active**.

## Not covered here (and why)

- **Who may accept, the order of the activation's refusals, an acceptance voided by a re-point, a removal or a detach, the races** —
  `AdrSuccessorAcceptTest`.
- **Rival successors, terminal or deleted predecessors, the activation block when the predecessor is no longer active, the parallel-write
  locks** — pinned by `AdrSuccessorTest` (including the parallel cases) and `SuccessorGraphTest`.
- **The role matrix of the detach** (Driver, Approver and ADMIN of the predecessor only; a terminal predecessor; a racing activation) —
  `AdrSuccessorDetachTest`; the browser shows the button only to whoever the server's `canDetachSuccessor` allows (unit tests).
- **The role matrix** (who may link) — `AdrSuccessorTest` and `AdrAccessTest`; the browser shows the controls only to whoever the server's
  `canEditSuccessor` allows (unit tests).
