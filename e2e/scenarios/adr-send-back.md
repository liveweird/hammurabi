# Send back and withdraw (the Approver returns an ADR for rework; a decision in force is revoked)

- **Spec**: [tests/adr-send-back.spec.ts](../tests/adr-send-back.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; three throwaway regular users — a Driver, an
  Approver and a Contributor — who do everything else (the Driver also seeds the ADRs through the API)
- **Owns** (exclusive server-side state): its three throwaway users (unique `E2E Driver-*` / `E2E Approver-*` / `E2E Contributor-*` names)
  and the ADRs (unique `e2e-adr-*` names) they raise — all deleted through the API after each test, the ADRs first

## Scenario: the Approver sends an ADR back with a reason, the Driver resubmits it, and the Approver activates and then withdraws it

1. A complete ADR naming the Approver is raised and sent to review through the API as the Driver. The Approver signs in, opens it and
   clicks **Send back to draft**.
   - *Expected*: a dialog "Send this ADR back to draft?" opens with an optional **Reason** field.
2. They write a reason and confirm.
   - *Expected*: the status reads **Solution draft** and the **Send back to draft** button is gone. They sign out.
3. The Driver signs in and opens the bell.
   - *Expected*: one unseen notice, "<Approver> sent <key> back to draft" (the bell names the move, not the reason).
4. They open the ADR.
   - *Expected*: the History lists "Sent back to draft" with "Reason: Add the cost of running it."
5. They click **Edit**, change the consequences, **Save**, then **Send to review**.
   - *Expected*: the saved text shows (the content was kept) and the status reads **In review**. They sign out.
6. The Approver signs in, opens the ADR and clicks **Activate**, then **Withdraw decision**.
   - *Expected*: the status reads **Active**; a dialog "Withdraw this decision?" opens.
7. They back out with **Cancel**, click **Withdraw decision** again, write a reason and confirm.
   - *Expected*: after backing out the status is still **Active**; after confirming it reads **Withdrawn**, "This ADR is closed: it is
     read-only." shows, and the History lists "Decision withdrawn" with its reason and, further down, the send-back reason too.

## Scenario: a Contributor cannot withdraw a decision in force: no button, and the API answers 403

1. An active ADR naming an Approver and a Contributor is prepared through the API (the Driver sends it to review, the Approver activates it).
2. The Contributor signs in and opens it.
   - *Expected*: the status reads **Active** and the Status section has no button at all, only "No status change is available to you."
3. The Contributor, and then the Driver, ask the API to withdraw it.
   - *Expected*: both get `403`; after a reload the ADR is still **Active**.

## Scenario: a Driver cannot take the Approver seat of a decision in force to withdraw it: the picker is locked and the API answers 403

1. An active ADR naming an Approver is prepared through the API (the Driver sends it to review, the Approver activates it).
2. The Driver signs in, opens it and clicks **Edit participants**.
   - *Expected*: the Approver picker is disabled and its hint says only the Approver or an administrator can change it once the ADR has been in review.
3. The Driver asks the API to replace the participants naming themselves Approver (and the Contributor Driver).
   - *Expected*: `403`.

## Not covered here (and why)

- **Who may send back, the role matrix, the reason's bounds, the notices, the pending-successor and issue consequences** — the server's
  `AdrSendBackWithdrawTest` and `AdrAccessTest`, the SPA's `AdrStatusControl` and `AdrSuccessorPanel` unit tests.
- **The email digest line for a send back** — `NotificationEmailTest`.
