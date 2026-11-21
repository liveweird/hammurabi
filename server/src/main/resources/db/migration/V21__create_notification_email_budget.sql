-- The per-actor mail budget (1.0.0 checkup C-SEC-4, 1.2.0): the most notices one actor's writes can make eligible for the email digest in
-- one UTC day (`notifications.email.maxNoticesPerActorPerDay`, default 200). Without it a Driver adding 99 people to ADR after ADR (each
-- write is one notice per person, nothing stops a script) makes the digest mail a flood of notices naming that actor through the shared
-- mailer. The notice itself (the bell) is always made; over the budget only its EMAIL is withheld for good, by stamping
-- `notifications.emailed_at` at insert ("settled, never mailed"; no new column on `notifications`). The unit is the notice, not the
-- distinct recipient: an ADR's audience is up to 99 people, so a count of people would neither fit honest use nor close the lever.
--
-- One row per (actor, UTC day) holds a counter: `admitted`, the notices of the day that stayed eligible, in the order they were made.
-- `exceeded_at` is the instant the first notice of the day was withheld (informational: the audit event
-- `notification.email_budget_exceeded` is emitted by every write that withholds something). The row is locked `FOR UPDATE` by the write
-- that makes notices, inside that write's transaction and as its FIRST lock (plain `FOR UPDATE` is right: nothing references this row).
-- The write RESERVES the account and the UTC day before it takes any other lock and the notices are charged to exactly that row, even if
-- the ADR's Approver changed or midnight passed in between, so the row sits in no lock cycle; two writes charged to one account
-- serialize on it, the budget holds under concurrency and no global lock is held while waiting.
-- It is NOT tied to the notices or the participants (a removal deletes those, never the budget), so adding and removing people cannot
-- reset it. The account charged is the actor, or for a write with no actor (the system: the expiry sweep, the reminders) the ADR's
-- current Approver; an ADR with no active Approver spends nothing.
--
-- Delete class (persistence.md): HARD delete. A counter of the day, not a record: the notice retention pass deletes the days before its
-- cutoff's (`idx_notification_email_budget_day`); an actor's rows go with the user row (a test purge cascades; accounts are only
-- soft-deleted). `day` is the epoch day (millis / 86400000, UTC).
CREATE TABLE notification_email_budget (
    actor_id    BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day         INTEGER NOT NULL,
    admitted    INTEGER NOT NULL DEFAULT 0,
    exceeded_at BIGINT,
    PRIMARY KEY (actor_id, day)
);
CREATE INDEX idx_notification_email_budget_day ON notification_email_budget (day);
