# The decision log (the read-only overview page)

- **Spec**: [tests/decision-log.spec.ts](../tests/decision-log.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast and the tag category; a throwaway Driver and a
  throwaway Approver (regular users) who raise and move the ADRs through the API and read the page in the browser
- **Owns** (exclusive server-side state): its two throwaway users (`E2E Log Driver-*`, `E2E Log Approver-*`), the ADRs they raise (unique
  `e2e-adr-log-*` names) and one throwaway tag category (`E2E Log Category-*` with an `e2e-tag-log-*` tag; the seeded categories are never
  touched) - all deleted through the API after each test (the ADRs, then the category, then the users)
- **Phase**: the page's sections list the newest few ADRs of EVERY user (capped), which a spec cannot own, so this spec runs in the
  `global-readers` project, after the parallel pool has finished and deleted its ADRs (`playwright.config.ts`)

## Scenario: an activated tagged ADR shows under its tag and in recently decided, and one expiring in five days shows in expiring soon

1. The admin creates a tag category with one tag. The Driver raises two complete ADRs naming the Approver: the first is tagged with the new
   tag, the second has an expiry date five days from now. Each goes to review (Driver) and is activated (Approver).
2. The Driver signs in and clicks **Decision log** in the navigation.
   - *Expected*: the page opens at `/decision-log` under the heading "Decision log".
3. They read **In force by tag**.
   - *Expected*: the category is a level-4 heading, the tag a level-5 heading, and the tagged ADR is a link ("ADR-NNNN name") in the list
     named after the category and tag.
4. They read **Recently decided**.
   - *Expected*: both ADRs are listed.
5. They read **Expiring soon**.
   - *Expected*: the second ADR is listed with "last day <that date>" and no "Overdue" badge; the first (no date) is not listed.
6. They click the tagged ADR in Recently decided.
   - *Expected*: the ADR's own page opens (`/adrs/<id>`) under its name.

## Scenario: an ADR in review is the Approver's own - highlighted, counted and linked to their queue - and plain to the Driver

1. The Driver raises a complete ADR naming the Approver and sends it to review.
2. The Approver signs in and opens `/decision-log`.
   - *Expected*: **Awaiting decision** lists the ADR with the "Needs your decision" badge and "Approver: <their name>", and a link "1 ADR is
     waiting for your decision" stands above the list.
3. They click that link.
   - *Expected*: the ADR list opens filtered to in review and the Approver role (`?status=IN_REVIEW&mine=1&participantRole=APPROVER`) and shows the ADR.
4. The Driver signs in and opens `/decision-log`.
   - *Expected*: the same ADR is listed in **Awaiting decision**, without the badge, and no "waiting for your decision" link is offered.

## Scenario: an Approver extends an active ADR out of the expiring queue and the Driver is told

1. The Driver raises a complete ADR with a last day inside the server's configured expiry window, sends it to review, and the Approver activates it.
2. The Approver signs in, opens `/decision-log`, and clicks the ADR's title in **Expiring soon**.
   - *Expected*: the ADR page opens at `/adrs/<id>#validity` and focus moves to **Validity**.
3. In **Validity**, they choose **Extend validity**, enter a later day that is still inside the server's configured horizon, and submit **Extend validity**.
   - *Expected*: the new last day is shown; the ADR remains in **Expiring soon** and in the fixed Active ADR list whose `expiresBefore` cutoff is the day after that horizon.
4. They return through the title link and extend validity again, this time beyond the horizon.
   - *Expected*: the ADR remains **Active**, **Lifecycle** shows the new last day, and History shows both dates.
5. They revisit `/decision-log` and the same fixed filtered ADR-list URL.
   - *Expected*: neither view includes the ADR now that its last day is beyond their cutoff.
6. The Driver signs in and opens the notifications drawer.
   - *Expected*: the existing expiry-change notice names the Approver, the ADR, and its new last day.

## Not covered here (and why)

- **The caps, the "+N more" links, the 30-day horizon, the overdue ordering, untagged ADRs, retired tags and the tag-group cap** — pinned by
  `AdrOverviewTest` and `AdrOverviewCapsTest` (server, with an injected "today") and `DecisionLog.test.tsx` (web); an e2e would need 21 ADRs
  per case against a stack shared with other specs.
- **The status strip's links** — pinned by `DecisionLog.test.tsx`.
