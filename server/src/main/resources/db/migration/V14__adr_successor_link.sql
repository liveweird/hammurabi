-- ADR successor links (0.4.0, `.claude/docs/adr-model.md` "Successors"): `supersedes_id` on the SUCCESSOR names the ADR it replaces (at most
-- one predecessor per ADR; a self-reference is refused by the CHECK, a longer cycle by the service under an advisory lock - an index
-- cannot see a cycle). The link is set while the successor is pre-active and takes effect when it becomes ACTIVE: the predecessor then moves
-- to SUPERSEDED in the same transaction. No ON DELETE rule: ADRs are only ever soft-deleted.
--
-- `uq_adrs_supersedes_open`: a predecessor has at most ONE successor still in play. A successor that ended without ever activating
-- (CANCELLED, WITHDRAWN) or after it did (EXPIRED, SUPERSEDED) leaves the index, so a predecessor can be re-linked after a cancelled
-- attempt; a soft-deleted successor leaves it too. The terminal names are the frozen `AdrStatus` values (the Kotlin enum is the
-- whitelist everywhere else - the V5 rule - but a partial-index predicate has to spell them).
ALTER TABLE adrs ADD COLUMN supersedes_id INTEGER REFERENCES adrs(id);
ALTER TABLE adrs ADD CONSTRAINT ck_adrs_supersedes_self CHECK (supersedes_id IS NULL OR supersedes_id <> id);
CREATE UNIQUE INDEX uq_adrs_supersedes_open ON adrs (supersedes_id)
    WHERE supersedes_id IS NOT NULL AND NOT marked_as_deleted AND status NOT IN ('CANCELLED', 'WITHDRAWN', 'EXPIRED', 'SUPERSEDED');
-- The reverse lookup ("which ADR replaces this one?"), including the terminal successors the unique index leaves out.
CREATE INDEX idx_adrs_supersedes ON adrs (supersedes_id) WHERE supersedes_id IS NOT NULL;
