# ADR history

- **Spec**: [tests/adr-history.spec.ts](../tests/adr-history.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; two throwaway regular users — a Driver (who
  makes the changes through the API and reads the history in the browser) and a Contributor
- **Owns** (exclusive server-side state): its two throwaway users (unique `E2E Driver-*` / `E2E Contributor-*` names) and the ADRs
  (unique `e2e-adr-*` names) the Driver raises — all deleted through the API after each test, the ADRs first

## Scenario: the history lists each change newest first, in words, with who made it

1. Through the API the Driver raises an ADR, edits its context, adds the Contributor and moves it to **Solution draft**.
2. The Driver opens the ADR in the browser and reads the History section.
   - *Expected*: four entries, newest first — "Status changed from Problem raised to Solution draft", "<Contributor> added as
     Contributor", "Edited" (with the line "Context changed") and "ADR raised" (with the line `ADR-NNNN "<name>"`); each carries
     "<Driver> · YYYY-MM-DD HH:mm".

## Scenario: the history pages ten events at a time

1. Through the API the Driver raises an ADR and edits its problem eleven times (twelve events).
2. They open the ADR.
   - *Expected*: ten "Edited" entries and no "ADR raised" on the first page.
3. They click **Next page** in the History section.
   - *Expected*: "ADR raised" and the one remaining "Edited" entry.

## Not covered here (and why)

- **System events (`EXPIRED`) and a deleted actor** — need the clock or a deleted account mid-test; covered by `adrEvents.test.ts` and the
  server's `AdrHistoryTest`.
- **Polish rendering** — the same keys in both languages are pinned by the locale parity test; the language switch itself is `i18n.spec.ts`.
