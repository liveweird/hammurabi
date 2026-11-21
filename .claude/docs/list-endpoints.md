### List endpoint conventions

> **Ported.** `server/src/main/kotlin/infra/paging/` (`Paging.kt`, `QueryParams.kt`, `PageResponse.kt`) is Lettuce's
> implementation, ported via Toadie; `GET /api/v1/users` is the reference implementation. The conventions below are the
> contract the package enforces.

**The generic list rules are owned by `api-guidelines/API-GUIDELINES.md`** (cite the rule IDs):

- Offset pagination `page`/`pageSize` (1-based, default 20, max 100; out-of-range → `400`) in the
  `{items, page, pageSize, total}` envelope; `total` is computed after filters, before pagination,
  with count + page rows in the **same `suspendTransaction`**; cursor paging is a documented
  per-endpoint opt-in [API-LIST-001/002, API-STRUCT-004].
- Sorting: `sort=field`/`-field`, comma-separated multi-field, validated against a per-endpoint
  whitelist → `400`; a field named twice (with or without `-`) or a list longer than the whitelist is a `400`
  (a computed sort costs SQL per entry; `SortListBoundsTest`); `id` asc always appended as tiebreaker; default `id` asc documented
  [API-LIST-003].
- Filtering: whitelisted equality filters on the field's own name plus `field[gte|gt|lte|lt]`
  bracket operators only where needed; strict `true`/`false` booleans; enums by string name; a repeated
  scalar key is a `400` (never silent first-value-wins; unknown parameter *names* stay ignored by deliberate
  leniency), see "Repeated keys" below [API-LIST-004].
- Free text: `q` first, per-column substring only when a UI requires it [API-LIST-005]. The first `q` consumer is `GET /users/directory?q=` (name OR email on
  one `containsNormalized` predicate for count and rows; `q` REQUIRED, 2 to 100 code points after trimming; pages capped at 20 via its own `DirectoryPageSize` component; spec parameter component `Q`); `GET /users` still filters per column.
- Naming: camelCase params; reusable `Page`/`PageSize`/`Sort` under `#/components/parameters`
  `$ref`'d from each list path; one `*Page` envelope schema per resource; sortable/filterable
  whitelists documented per path [API-NAME-001..004].

**Implementation shape:** `ApplicationCall.parsePaging(sortable = setOf(...))` parses
`page`/`pageSize`/`sort` from the query string against the per-endpoint whitelist into a
`PageRequest` (appending the `id`-asc tiebreaker); `Query.applyPaging(req, columns)` applies
`.orderBy(...)` + `.limit(...).offset(...)` to an Exposed `Query` via the service's file-level
`SORTABLE_COLUMNS` map (the two whitelists must agree — a missing column is an `error()`, i.e. a
500-grade invariant violation). Validation failures throw `BadRequestException` → `400` +
`ProblemDetail`. The service runs `count()` + the page rows against ONE shared predicate in ONE
`suspendTransaction` and returns an `XListResult(items, total)`; the route responds
`paging.toPage(items, total)` and the DTO file declares `typealias XPageResponse = PageResponse<XResponse>`.
New list endpoints copy `users/` (service `list`, routes `get<X>`, the OpenAPI `$ref`s to the shared
`Page`/`PageSize`/`Sort` parameters and a per-resource `*Page` schema) rather than re-parsing params.

**Substring filters:** every per-column substring filter must be case- AND accent-insensitive —
use `containsNormalized` (`infra/db/Sql.kt`, rendering
`LOWER(public.unaccent(col)) LIKE (SELECT concat('%', <escaped LOWER(public.unaccent(?))>, '%'))`, extension enabled in V4; the LIKE
metacharacters of the needle are escaped AFTER the fold, in SQL: unaccent maps the fullwidth `％ ＿ ＼` to ASCII, so an earlier escape would be undone, `ContainsPatternTest`); never
hand-roll `lowerCase() like containsPattern(...)`, and keep unaccent-before-LOWER (the reverse
breaks uppercase-diacritic input under a C-locale database). The SPA mirrors the rule via the
theme-level `foldedOptionsFilter` (`web/src/utils/text.ts`).

**Repeated keys (`IN`).** A scalar key repeated is a `400` (`singleValue`); a parameter documents `IN` semantics through the `repeatedValues` family (`repeatedLongs`/`repeatedStrings` in `infra/paging/QueryParams.kt`), declared in the spec as `type: array` with `style: form`, `explode: true` and `minItems`/`maxItems`, with duplicates collapsed (`distinct()`) before the bound applies. **Ledger (the endpoints that use `IN` semantics; Flow kept its ledger in a report doc that was not ported):** `GET /adrs?status=` (any-of, `repeatedStrings`, at most one per `AdrStatus` — eight — duplicates collapsed, an unknown name `400`; `minItems: 1`, `maxItems: 8`). `GET /adrs/{id}/discussion?kind=` (any-of, `repeatedStrings`, at most one per `DiscussionKind` - four -, an unknown name `400`; `minItems: 1`, `maxItems: 4`), next to its single-valued `state` (an optional enum, `400` when repeated or unknown); that list is the sub-collection precedent: oldest first by default (`createdAt`, `id` ascending), sortable by `id` and `createdAt`. `GET /adrs?tag=` (any-of, `repeatedValues`, at most 20 (`MAX_TAG_FILTERS`), duplicates collapsed after normalising (trim, lower-case), a malformed name `400`, an unregistered one matches nothing; `minItems: 1`, `maxItems: 20`; the ADRs carrying at least one of the tags, retired ones included). Also on the ADR list (0.5.0; the filters and the search are `adr-model.md` "Search"): `text` (full-text, distinct from `q`; a search page is at most 20 and sorts by `relevance`, `400` without `text`), the day ranges, `expiresBefore` and the strict booleans; every filter is an AND, and the query string and a lens's `filters` share ONE validation (`AdrFilterDocument.normalized()`). `GET /lenses` is paged too (`mine`, `visibility`; name order). `q` is optional (the `OptionalQ` component), `participantId` takes an optional `participantRole` (`400` without it; `GET /users?role=` is the account role, a different name on purpose), the presence booleans are `hasOpenIssues`/`hasPredecessor`/`hasSuccessor`, and `sort=status` orders by lifecycle position (a `CASE`, so `applyPaging` takes `Expression`s, Adder's departure from the verbatim port), default `-number`.

**Shape exceptions (two), each capped and documented where it lives:** `GET /adrs/export` (a streamed ZIP, `adr-model.md` "Export") and `GET /adrs/overview` (a capped document, `adr-model.md` "Overview"). Any other purpose-built document answers outside the envelope only with a documented cap and a line here; Flow's report drill lists were the precedent.

**Sub-collections and facets:** a record's own unbounded sub-collection is paged like any other list; the plain `{items}`
wrapper is reserved for genuinely bounded, admin-curated sets. Facet counts (`GET .../facets`) are not implemented — port Covenant's
pattern with the first filterable list that needs them.

**Capped totals.** `GET /notifications` (0.6.0, the caller's own notices) is the one list whose `total` is CAPPED: 1000 (`MAX_COUNTED_NOTICES`; the count reads at most 1001 rows and a page starting at row 1000 is empty without the OFFSET), because a recipient's rows are not bounded; filter `seen` (strict boolean), sort `id` (default, newest first) and `createdAt`.
