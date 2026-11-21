-- Notifications (0.6.0, `.claude/docs/adr-model.md` "Notifications"; Adder's V18 + V22, reshaped from chat to ADRs): `notifications` holds one
-- row per thing a person should look at on an ADR they take part in, `user_notification_preferences` the person's switches.
--
-- A notice is a POINTER, ids and one short enum-valued `detail` only: no ADR text, name or body is stored, so none can leak, none needs
-- scrubbing, and a renamed ADR or person shows its current name (read when the page is read). `notification_type` carries NO CHECK: the
-- Kotlin enum is the whitelist and its names are persisted and frozen (the V5 rule); a stored name an older or newer build does not know is
-- invisible to it, never fatal. `detail` is what the type needs and nothing more: the DACI role (ROLE_ASSIGNED, ROLE_REMOVED), the new
-- status (STATUS_CHANGED), the entry kind (DISCUSSION_POSTED, DISCUSSION_SETTLED) or the expiry date (ADR_EXPIRED, EXPIRY_REMINDER);
-- null otherwise. `other_adr_id` is the second ADR of a successor notice (the successor of ADR_SUPERSEDED and of SUCCESSOR_LINKED /
-- SUCCESSOR_UNLINKED, which are made on the PREDECESSOR: `adr_id`). `actor_id` is the person whose write caused it; null = the system
-- (the expiry sweep, the reminder pass). `seen_at` is null while unseen.
--
-- Delete class (persistence.md): HARD delete. A notice is a pointer, not a record: it goes with what it points at (its ADR, a user: ON
-- DELETE CASCADE; ADRs and users are only soft-deleted, so a soft-deleted ADR's notices are hidden by the list and a test purge
-- cascades) and by retention (a sweep deletes rows older than `notifications.retentionDays`).
--
-- `emailed_at` is the digest's CLAIM: the sweep stamps it in a short transaction BEFORE it sends, so a notice is mailed at most once (a
-- failed send is audited and not retried) and two processes cannot send one notice twice (`FOR UPDATE SKIP LOCKED` on the claim). It is
-- null until claimed; a notice that was claimed and skipped (a kind the recipient switched off) is never mailed later.
-- The indexes: the recipient's list (newest first) and the badge (unseen only), the retention purge (the oldest rows straight off
-- `idx_notifications_created`) and the digest's claim (`idx_notifications_email_due`: the oldest unseen, unclaimed notices).
CREATE TABLE notifications (
    id                SERIAL      PRIMARY KEY,
    recipient_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(40) NOT NULL,
    actor_id          BIGINT               REFERENCES users(id) ON DELETE CASCADE,
    adr_id            INTEGER     NOT NULL REFERENCES adrs(id)  ON DELETE CASCADE,
    other_adr_id      INTEGER              REFERENCES adrs(id)  ON DELETE CASCADE,
    detail            VARCHAR(40),
    created_at        BIGINT      NOT NULL,
    seen_at           BIGINT,
    emailed_at        BIGINT
);
CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, id);
CREATE INDEX idx_notifications_unseen ON notifications (recipient_id, id) WHERE seen_at IS NULL;
CREATE INDEX idx_notifications_created ON notifications (created_at);
CREATE INDEX idx_notifications_email_due ON notifications (created_at) WHERE seen_at IS NULL AND emailed_at IS NULL;

-- `user_notification_preferences` (Lettuce's V84, Adder's V22): a row is a DISABLED (kind, channel) pair, no row means on, so a new user and a
-- new kind are on without a write (the V5 disabled-set model, replaced wholesale by one PUT). `notification_type` and `channel` carry NO
-- CHECK: the Kotlin enums are the whitelists and their names are persisted and frozen; a stored name this build does not know is ignored by
-- every reader. `channel` is IN_APP (the bell: a switched-off kind makes no notice for the person) or EMAIL (the digest).
--
-- Delete class (persistence.md): HARD delete. The table is a flag join with a wholesale-replace PUT (the `user_disabled_features` class); a
-- user's rows go with the user row (a test purge cascades; accounts are only soft-deleted, and a deleted account is never mailed).
CREATE TABLE user_notification_preferences (
    user_id           BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(40) NOT NULL,
    channel           VARCHAR(10) NOT NULL,
    PRIMARY KEY (user_id, notification_type, channel)
);
