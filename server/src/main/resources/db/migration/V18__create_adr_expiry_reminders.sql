-- The expiry reminder's sent marker (0.6.0, `.claude/docs/adr-model.md` "Notifications"; Covenant's `ReleaseLineReminders` dedupe shape): one row
-- per ADR holds the expiry date it was last reminded FOR. The reminder pass stamps it in the same transaction as the notices it makes, and
-- skips an ADR whose marker equals its current `expires_on`, so a reminder is sent once per ADR per expiry date and a CHANGED date re-arms it
-- (the marker then differs). `sent_at` is epoch millis.
--
-- Delete class (persistence.md): HARD delete. A marker is a fact about a notice already made, not a record: it goes with its ADR (ON DELETE
-- CASCADE; ADRs are only soft-deleted, so a test purge cascades).
CREATE TABLE adr_expiry_reminders (
    adr_id     INTEGER     PRIMARY KEY REFERENCES adrs(id) ON DELETE CASCADE,
    expires_on VARCHAR(10) NOT NULL,
    sent_at    BIGINT      NOT NULL
);
