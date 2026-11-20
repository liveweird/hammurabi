-- The ADR discussion (0.3.0, `.claude/docs/adr-model.md` "Discussion"; Covenant's `version_review_entries`, ported and reshaped): ONE table for
-- the open decision log's talk. `kind` COMMENT / QUESTION are accepted while the ADR is pre-active, ISSUE / ESCALATION while it is ACTIVE
-- (the service judges the window under the ADR row lock; no CHECK on enum-valued columns, the Kotlin enums are the whitelists - the V5
-- rule). `status` is the entry's state: NULL for a COMMENT (it has none) and for a deleted entry (a tombstone is in no state, so no
-- status filter matches it), OPEN / ANSWERED for a QUESTION, OPEN / RESOLVED for an ISSUE or ESCALATION. `state_changed_by/at` name
-- whoever last answered, resolved or REOPENED the entry (a reopen overwrites them with the reopener) and `resolution` (an optional
-- note, empty when none) is cleared on a reopen. `body` is Markdown, at most 10,000 characters; a SOFT DELETE (`marked_as_deleted`)
-- leaves a tombstone: the row, its kind, author and `created_at` stay, everything else is wiped (the text is really gone, not merely
-- hidden) and `deleted_at` stamps the removal - `ck_adr_discussion_tombstone` pins exactly that shape. `edited_at` is set by the
-- author's edit. Entries are never hard-deleted (`adr_id` cascades only for a hard purge of an ADR, which soft-deletes today). Authors
-- and resolvers may be soft-deleted users since: readers resolve the name through `users` regardless.
CREATE TABLE adr_discussion (
    id SERIAL PRIMARY KEY,
    adr_id INTEGER NOT NULL REFERENCES adrs(id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL,
    status VARCHAR(10),
    body TEXT NOT NULL CHECK (char_length(body) <= 10000),
    resolution TEXT NOT NULL DEFAULT '' CHECK (char_length(resolution) <= 2000),
    author_id BIGINT NOT NULL REFERENCES users(id),
    created_at BIGINT NOT NULL,
    edited_at BIGINT,
    state_changed_by BIGINT REFERENCES users(id),
    state_changed_at BIGINT,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at BIGINT,
    CONSTRAINT ck_adr_discussion_body
        CHECK (marked_as_deleted OR char_length(btrim(body, E' \t\r\n')) > 0),
    CONSTRAINT ck_adr_discussion_tombstone
        CHECK ((deleted_at IS NOT NULL) = marked_as_deleted
               AND (NOT marked_as_deleted
                    OR (status IS NULL AND body = '' AND resolution = ''
                        AND edited_at IS NULL AND state_changed_by IS NULL AND state_changed_at IS NULL)))
);

-- The page of one ADR's entries, oldest first (the default order): `(adr_id, created_at, id)`.
CREATE INDEX idx_adr_discussion_adr_created ON adr_discussion (adr_id, created_at, id);
-- The counts the ADR rows carry (`openIssueCount`, `unansweredQuestionCount`): only OPEN entries of a live row are in the index, so a
-- list page's one aggregate (`WHERE adr_id IN (...) GROUP BY adr_id`, with the predicate spelled as LITERALS so a prepared plan can
-- prove it implies the index's) reads a handful of entries, however long the discussions grow.
CREATE INDEX idx_adr_discussion_open ON adr_discussion (adr_id, kind) WHERE status = 'OPEN' AND NOT marked_as_deleted;
