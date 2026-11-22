# My work

- **Spec**: [tests/my-work.spec.ts](../tests/my-work.spec.ts)
- **Actors**: the seed admin through the API, plus a throwaway Driver and reviewer
- **Owns** (exclusive server-side state): its two throwaway users and the three ADRs they raise. The test cleans up through the API.

## Scenario: My work shows my ADR roles and opens the relevant sections

1. The Driver submits an ADR to the reviewer and asks a question. The reviewer drives a second decision that is active and expires in three days. The first Driver owns an unrelated ADR.
2. The reviewer signs in and opens `/`.
   - *Expected*: it redirects to `/my-work`, with an active sidebar entry. The review needs a decision, the expiry is shown with the other Approver named, and the unrelated ADR is absent. The populated view passes the axe scan. At an intermediate viewport width, activity stacks below attention to fit next to the sidebar.
3. The reviewer opens the expiring ADR from the watch row.
   - *Expected*: Validity receives focus. The reviewer has no Extend validity action on the ADR they drive.
4. The reviewer returns to My work and selects **Approver**.
   - *Expected*: the role appears in the URL and in the all-ADRs link. The ADR they drive drops from the page.
5. The reviewer follows the question from **Recent activity**.
   - *Expected*: the ADR opens on the Discussion tab, its heading receives focus, and the question is visible.
