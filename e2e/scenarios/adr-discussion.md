# ADR discussion

- **Spec**: [tests/adr-discussion.spec.ts](../tests/adr-discussion.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; three throwaway regular users — a Driver (raises
  the ADRs through the API), an Approver and an Asker with no role, who posts
- **Owns** (exclusive server-side state): its three throwaway users (unique `E2E Driver-*` / `E2E Approver-*` / `E2E Asker-*` names) and the
  ADRs (unique `e2e-adr-*` names) the Driver raises with the Approver named — all deleted through the API after each test, the ADRs first

## Scenario: a comment and a question are posted on a draft and the Driver marks the question answered

1. The Driver raises a complete ADR with the Approver through the API. The Asker signs in, opens it and opens the **Discussion** tab.
   - *Expected*: "Nothing has been said yet." and a composer ("Your comment or question") with the kind set to Comment.
2. They write a comment with some Markdown and press **Post comment**.
   - *Expected*: a "Comment by <Asker>" entry renders the Markdown (the bold run is bold), the composer is empty again.
3. They switch the kind to **Question**, write a question and press **Ask question**.
   - *Expected*: a "Question by <Asker>" entry with the **Open** badge; the tab is named "Discussion unanswered: 1"; an axe scan of the
     page reports zero violations.
4. The Asker signs out; the Driver signs in, opens the ADR's Discussion tab and presses **Mark answered** on the question.
   - *Expected*: both entries are listed; the question shows **Answered** and "Answered by <Driver> on YYYY-MM-DD"; the tab no longer
     counts anything; the History lists "Question answered", "Question asked", "Comment added", newest first.

## Scenario: once the ADR is active the discussion takes no new comments: the composer is gone and the API refuses

1. The Asker posts a comment on the draft through the API; the Driver sends the ADR to review and the Approver activates it, both through the API.
2. The Asker opens the ADR and its **Discussion** tab.
   - *Expected*: the earlier comment is still readable; the note "Comments and questions are taken until the ADR is active. What was said
     stays readable." replaces the composer — no textbox and no **Post comment** button.
3. Through the API the Asker posts another comment, then an issue.
   - *Expected*: the comment is `409` of the typed problem `urn:hammurabi:problem:discussion-closed`; the issue is `201`.

## Scenario: an issue is raised on an active ADR, counted on the list, and resolved by the Approver

1. The Driver raises a complete ADR with the Approver, who activates it through the API (after the Driver sends it to review).
2. The Asker opens the ADR and its **Issues** tab.
   - *Expected*: "No issues have been raised." and a composer ("Describe the issue").
3. They write an issue and press **Raise issue**.
   - *Expected*: an "Issue by <Asker>" entry with the **Open** badge and no **Resolve** button (the author cannot resolve); the tab is
     named "Issues open: 1"; an axe scan of the page reports zero violations.
4. They open the ADR list.
   - *Expected*: the ADR's row shows the open-issues badge (its hidden text reads "Open issues: 1").
5. The Asker signs out; the Approver signs in, opens the ADR's Issues tab, presses **Resolve**, writes a note in the dialog and confirms.
   - *Expected*: the entry shows **Resolved**, the note and "Resolved by <Approver> on YYYY-MM-DD"; the tab no longer counts anything;
     the History lists "Issue resolved"; on the ADR list the row no longer shows an open-issues badge.

## Not covered here (and why)

- **Editing, deleting (the tombstone) and the escalation kind** — the same composer, card and verdicts; covered by `DiscussionEntryCard.test.tsx`,
  `AdrDiscussion.test.tsx` and the server's `AdrDiscussionTest` / `AdrDiscussionAccessTest`.
- **The role matrix** (who may edit, delete, answer, resolve) — the server's, pinned by `AdrDiscussionAccessTest`; here only what a person sees.
- **Polish rendering** — the same keys in both languages are pinned by the locale parity test; the language switch itself is `i18n.spec.ts`.
