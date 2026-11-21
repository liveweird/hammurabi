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

**Wire names (frozen at 1.0.0; C-API-1, ported from Adder's C-API-5).** A reference to a person is `<role>Id` (`managerId`, `authorId`, `actorId`, `participantId`, `stateChangedById`, and `createdById` for the past-participle roles), its display text `<role>Name`, and "the account was since deleted" `<role>Deleted` (`authorDeleted`, `actorDeleted`); a person is never a nested `{id, name, deleted}` object (the discussion's `author` and `stateChangedBy` were flattened). Plain `userId` only where the row IS the user (`AdrParticipant {userId, name, role}`, `LoginResponse`, a path segment). A filter that holds a person is `<role>Id` too, and its modifier carries the prefix (`participantId` + `participantRole`, never a bare `role`, which stays the account role of `GET /users?role=`). A presence filter on a relation is `has<Thing>` (`hasPredecessor`, `hasSuccessor`, `hasOpenIssues`), never the name of the relation itself (`supersedes` / `supersededBy` are the link OBJECTS on an ADR). A state field that is not the ADR's lifecycle is `state` (the discussion's), `status` is the ADR's. A request or filter key and the response property it mirrors share one name, and a PUT takes the shape the GET answers (the notification preferences' `kinds`). Audit keys, database columns, stored event params and the infra `EventLogRow` keep their names (`managerUserId` in `team.created`). A rename of a stored name is a migration in the same commit (V20 for the lens documents).

**Deliberate exceptions to the template** (checkup C-DOC-5, C-CONV-1; a new exception is added to this list with its reason in the commit that introduces it):

- **String-routed handlers.** The public, unauthenticated entry points are plain `post("/api/v1/login")`-style routes, not `@Resource` types: `auth/AuthRoutes.kt` (login, refresh, logout), `auth/MfaRoutes.kt`, `auth/PasswordResetRoutes.kt` and `plugins/Health.kt`. They have no path or query parameter for a resource class to type and they are the `security: []` surface, so the list of public paths stays greppable as literals.
- **A route module that builds its own service.** `notifications/NotificationPreferenceRoutes.kt` creates `NotificationPreferenceService` itself (nothing but its routes needs the instance; the digest reads the switches through `emailDisabledKinds`), and `telemetry/TelemetryRoutes.kt` builds its store and reports (the sampler is a separate module that shares only the table). Every other service is published by `Database.kt`.
- **Answers outside the paged envelope.** The ADR export and overview (`list-endpoints.md` "Shape exceptions"), the telemetry reads (`GET /telemetry/current|history`: a fixed-section document over a bounded time range, ADMIN only), the notification preferences (one entry per notice kind, a fixed set) and the tag-category registry (`{items}`, ADMIN-curated and small).
- **A literal soft-delete predicate.** `adrs/AdrDiscussionService.kt` (`countsOf`) and `adrs/AdrListQuery.kt` (`adrIdsWithOpenIssues`) spell `markedAsDeleted = false` and the OPEN states as SQL literals instead of `active()`: a generic prepared plan cannot prove a bind parameter implies the partial index `idx_adr_discussion_open`, so the literal is what makes the index usable (commented at both sites).
- **A validator in a routes file.** `users/UserRoutes.kt` keeps `validatePassword` (shared by create and change) beside the routes that use it; it moves into a `UserValidation.kt` when the rules outgrow it.
Sibling precedents the ADR core ported (`product.md` "Donors"): Toadie's `X-Expected-Revision` whole-document
PUT, Adder's `EventLog`, Covenant's 409-on-illegal-move lifecycle and its periodic date sweep.
