### Feature conventions

The shape every new feature follows; read in full before adding a feature, route, service, migration or page.

**Feature template — copy `teams/` (a small ADMIN-curated registry)**: it is the
only shape the foundation ships. `<feature>/<Entity>.kt` (request/response DTOs + `toResponse`)
with the `validateX` free function enforced by route AND service (in the DTO file, or a sibling
`<Entity>Validation.kt` once the rules outgrow it), `<Entity>Routes.kt` (`@Resource` typed routes
under `/api/v1/...` + `configureXRoutes()` reading services from `attributes`, `audit(...)` on
every mutation, authorization BEFORE body decoding so 403 wins over 400 — declarative and flat; past
~100 lines it delegates to private `Route.xxx(deps)` functions grouped by concern rather than
splitting into more files, which is exactly what `config/detekt/detekt.yml`'s `LongMethod`
(threshold 100) and `CyclomaticComplexMethod` (excludes `*Routes.kt`) overrides protect),
`<Entity>Service.kt` (Exposed `object` table nested inside the service, `suspendTransaction`,
soft-delete via `marked_as_deleted` + partial unique indexes, list = count + rows on one
predicate), a `V<n>__description.sql` migration (+ its checksum pin in `MigrationChecksumTest`),
spec paths in `openapi/documentation.yaml`, `cd web && npm run gen:api` (same commit), lazy pages +
`NAV_SECTIONS` entries (`web/src/utils/navigation.ts`), and an e2e spec + scenario doc +
coverage-map line. The ADR core (`adrs/`, `adr-model.md`) is the first fuller shape: a sub-collection written as a diff (`AdrParticipantService`), a state machine (`AdrLifecycle`) and pure verdicts (`AdrAccess`) beside the service, an append-only event log (`infra/db/EventLog.kt`), a numbered counter under an advisory lock, an `X-Expected-Revision` document PUT, and a periodic sweep on `backgroundScope`.

**Deliberate exceptions to the template.** None in v0.1.0. A new exception is added to this list with its reason in the commit that introduces it.
Sibling precedents the ADR core ported (`product.md` "Donors"): Toadie's `X-Expected-Revision` whole-document
PUT, Adder's `EventLog`, Covenant's 409-on-illegal-move lifecycle and its periodic date sweep.
