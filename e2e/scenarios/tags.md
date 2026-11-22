# Tag registry and ADR tags

- **Spec**: [tests/tags.spec.ts](../tests/tags.spec.ts)
- **Actors**: the seed administrator (curates the category through the UI; creates and deletes the cast through the API); one throwaway regular user
  — the Tagger, who raises the ADR through the API and tags it in the browser
- **Owns** (exclusive server-side state): its throwaway user (unique `E2E Tagger-*` name), its one ADR (unique `e2e-adr-*` name) and its one
  throwaway category (unique `E2E Category-*` name, `e2e-tag-*` tags) — all deleted through the API after each test (the ADR first, then the
  category, then the user). The tag registry is SHARED, append-only state: the seeded Domain and Scope categories are never touched, and a
  tag name is unique for good (even once retired), so every tag carries a per-run suffix.

## Scenario: an admin curates a category, a Driver tags an ADR with it, the list filter finds it and the API refuses an unknown tag

1. The admin signs in and opens **Tags** (`/tags`), presses **New category**, fills a unique name and a description, types two tags (Enter after
   each; the second in capitals) and presses **Create**.
   - *Expected*: the POST answers 201; the table row shows the category with both tags as pills, the capitalised one folded to lower case.
2. The Tagger raises an ADR through the API, signs in and opens it. The **Tags** section says "No tags"; they press **Edit tags**, open the
   picker, choose the first tag (the options are grouped by category), close the list with Escape and press **Save tags**.
   - *Expected*: the section lists the tag as a pill and offers **Edit tags** again; the History reads "Tags changed" with "Added: <tag>".
3. On the ADR list they open **Filters**, pick the tag in the **Tags** filter.
   - *Expected*: the URL carries `tag=<tag>`; the row of the ADR is listed with the tag pill. Asking for the OTHER tag of the category lists no
     row of this ADR.
4. Through the API the Tagger sets the tags to the registered one plus an unregistered name.
   - *Expected*: `400`; the ADR keeps its one tag and its revision.

## Scenario: a regular user reads the registry through the API but has no Tags page and no writes

1. The Tagger signs in.
   - *Expected*: the nav has a **Teams** link and no **Tags** link. Opening `/tags` lands on `/my-work`.
2. Through the API they read the registry and try to create a category.
   - *Expected*: the read is `200` and lists the seeded Domain and Scope categories; the create is `403`.

## Not covered here (and why)

- **Retired tags, revival, one category per tag, name clashes, the 100/20 caps, normalisation** — pinned by the server suite (`TagCategoryTest`,
  `AdrTagsTest`) and the component tests; the journey proves the happy path and the strictness at the API edge.
- **The grouped tag picker's rendering of many categories** — the unit tests (`AdrTagsPanel`, `tagOptionGroups`); the seeded Domain and Scope
  categories are shared state the spec does not read.
