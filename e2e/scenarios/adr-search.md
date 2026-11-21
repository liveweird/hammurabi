# ADR search and combined filters

- **Spec**: [tests/adr-search.spec.ts](../tests/adr-search.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; one throwaway regular user who raises the ADRs
  through the API and searches in the browser
- **Owns** (exclusive server-side state): its throwaway user (a unique `E2E Searcher-*` name) and the ADRs (unique `e2e-adr-*` names) it
  raises — all deleted through the API after each test, the ADRs first

## Scenario: full-text search finds an ADR by a word in its decision, ignoring accents, and highlights the hit

1. The user raises two ADRs through the API: one whose decision holds a unique word with Polish letters ("Żółwik…"), one about something else.
2. They open the ADR list and type the word with plain letters ("zolwik…") in **Search**.
   - *Expected*: the row of the first ADR is listed; its snippet marks the word in its stored spelling; the other ADR is not listed; the URL
     carries `text=`; the list says it is sorted by relevance.
3. They reload the page.
   - *Expected*: the box still holds the word and the row is still listed.
4. They search for a word nobody wrote, then press **Clear search**.
   - *Expected*: "No ADRs match these filters" shows for the unknown word; after clearing, the text is gone from the URL.

## Scenario: the filters combine with the search and the whole view is a link that round-trips

1. The user raises three ADRs about the same unique word through the API: a draft and a problem-raised one, both expiring just over a year from today, and one
   with no expiry date.
2. They open the list with the word in the URL (`?text=`): all three are listed.
3. They open **Filters** and set **Expires before** to a day a year after that expiry (both dates are derived from the current date).
   - *Expected*: the ADR with no date drops out; the two dated ones stay.
4. They pick the **Solution draft** status.
   - *Expected*: only the draft remains; the URL carries `text`, `expiresBefore` and `status`.
5. They set **Created to** to 2000-01-01.
   - *Expected*: "No ADRs match these filters"; the Filters button counts three active filters (the search box is not one of them).
6. They copy the URL, leave to the plain list and open the copied link.
   - *Expected*: the search box, the date and the rows are restored; clearing **Created to** brings the draft back and removes the key from the URL.

## Not covered here (and why)

- **Ranking, weights, prefixes, every-word-matches, the validation, the deadline and the cap, the filter boundaries** — pinned by `AdrSearchTest`,
  `AdrFiltersTest`, `SearchBudgetTest` and `FtsTest` (server).
