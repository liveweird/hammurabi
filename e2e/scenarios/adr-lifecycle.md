# ADR lifecycle (raise, draft, review, activate; cancel; withdraw; the Lifecycle tab)

- **Spec**: [tests/adr-lifecycle.spec.ts](../tests/adr-lifecycle.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; two throwaway regular users — a Driver and an
  Approver — who do everything else in the browser
- **Owns** (exclusive server-side state): its two throwaway users (unique `E2E Driver-*` / `E2E Approver-*` names) and the ADRs (unique
  `e2e-adr-*` names) they raise — all deleted through the API after each test, the ADRs first

## Scenario: a Driver raises an ADR, drafts it and sends it to review, and the Approver activates it

1. The Driver signs in, opens **New ADR** (`/adrs/new`), fills a unique name and the problem, picks the Approver in the **Approver**
   picker and clicks **Create**.
   - *Expected*: the app lands on the new ADR's page — its name as the heading, status **Problem raised** in the Status section.
2. They click **Move to draft**.
   - *Expected*: the status reads **Solution draft** and the section lists what entering review still needs: context, decision,
     consequences (the Approver is already named).
3. They click **Send to review** too early.
   - *Expected*: an inline alert says the ADR is not ready for that move; the status stays **Solution draft**.
4. They click **Edit**, write the context, decision and consequences, and **Save**.
   - *Expected*: the saved text shows in the sections.
5. They click **Send to review**, then sign out.
   - *Expected*: the status reads **In review**.
6. The Approver signs in and opens the ADR.
   - *Expected*: no **Edit** button and no **Send to review** (the Approver decides, the Driver writes).
7. They click **Activate**.
   - *Expected*: the status reads **Active**, there is still no **Edit** (the content is frozen) and the History lists
     "Status changed from In review to Active".

## Scenario: a Driver cancels an ADR after confirming

1. The Driver (the ADR was raised for them through the API) opens the ADR and clicks **Cancel ADR**.
   - *Expected*: a confirmation dialog "Cancel this ADR?" opens.
2. They back out with **Cancel**.
   - *Expected*: the dialog closes and the status is still **Problem raised**.
3. They click **Cancel ADR** again and confirm in the dialog.
   - *Expected*: the status reads **Cancelled**, "This ADR is closed: it is read-only." shows, and neither **Edit** nor any move button remains.

## Scenario: the Approver withdraws an ADR in review

1. A complete ADR with the Approver named is raised and sent to review through the API as the Driver.
2. The Approver signs in, opens it and clicks **Withdraw**, then confirms in the dialog.
   - *Expected*: the status reads **Withdrawn** and the ADR is shown as closed and read-only.

## Scenario: the Lifecycle tab shows the workflow with where the ADR is and the moves the Approver can make now

1. A complete ADR with the Approver named is raised and sent to review through the API as the Driver.
2. The Approver signs in, opens it and opens the **Lifecycle** tab.
   - *Expected*: the diagram (alt text ending "This ADR is: In review.") shows, with **In review** as the current node.
3. They read the arrows out of **In review**.
   - *Expected*: the Approver's moves (**Activate**, **Withdraw**, and the send back to **Solution draft**) are highlighted; **Cancel ADR** (the Driver's) is not; the text list "Moves from In review" says "You can do this now" beside **Activate** and not beside **Cancel ADR**.

## Not covered here (and why)

- **The expiry sweep (`ACTIVE` → `EXPIRED`) and the successor move** — clock-driven or 0.4.0; covered by the server's
  `AdrExpirySweepTest`.
- **Stale-revision conflicts** — need two writers racing; covered by the SPA unit tests (`AdrWriteError`, `AdrEditForm`) and the server.
