-- The ADR history (0.2.0, `.claude/docs/adr-model.md` "History"): append-only, no update and no delete (the shape of Adder's
-- `thread_events`). One immutable STRUCTURED event per mutation (`event_type` + a JSON object of string params the SPA renders in
-- the viewer's language), committed in the same transaction as the write it describes; the security trail stays the AUDIT-marked logs.
-- `user_id` is the acting user, NULL for a SYSTEM event (the expiry sweep): ON DELETE RESTRICT, an actor is never hard-removed from
-- under the history (users soft-delete anyway). `adr_id` cascades only for a hard purge (ADRs soft-delete). No CHECK on `event_type`:
-- the Kotlin enum is the whitelist (the V5 rule). Events are read newest first: `(adr_id, created_at, id)` serves the paged list.
CREATE TABLE adr_events (
    id SERIAL PRIMARY KEY,
    adr_id INTEGER NOT NULL REFERENCES adrs(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    created_at BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    params TEXT NOT NULL
);
CREATE INDEX idx_adr_events_adr_created ON adr_events (adr_id, created_at, id);
