-- Successor acceptance (1.0.0, checkup C-SEC-1, `.claude/docs/adr-model.md` "Successors"): a successor replaces an ACTIVE predecessor only
-- once that predecessor's Approver (or ADMIN) accepted the link. `supersedes_accepted_id` sits on the SUCCESSOR beside `supersedes_id` and
-- names the predecessor the acceptance was given FOR; the link is accepted when the two are equal. So an acceptance belongs to ONE link:
-- re-pointing the successor to another ADR, unlinking it or detaching it must clear the marker: the service does so in the same UPDATE,
-- and the CHECK below refuses any row whose marker outlives or disagrees with its link (`IS NOT DISTINCT FROM`: a plain `=` against a
-- cleared link is NULL, which a CHECK lets through), so nothing carries over to a new link.
--
-- Upgrade rule (decision, `product.md` "Assumptions"): a link already IN EFFECT (the successor has its SUCCESSOR_ACTIVATED event, so the
-- predecessor was superseded in the same transaction) is backfilled as accepted - it was applied under the old rules and stands. A link still
-- PENDING stays unaccepted: its predecessor's Approver has to accept it before the successor can activate. Rolling back to 0.9.x ignores the
-- column, but 0.9.x re-pointing, unlinking or detaching an ACCEPTED pending link would trip the CHECK (a 500), so BEFORE the old release
-- starts clear the pending markers only (clearing every marker would erase the backfill of the links in effect):
--   UPDATE adrs SET supersedes_accepted_id = NULL WHERE NOT EXISTS
--     (SELECT 1 FROM adr_events e WHERE e.adr_id = adrs.id AND e.event_type = 'SUCCESSOR_ACTIVATED');
-- (`persistence.md` "Successor acceptance (V19)").
ALTER TABLE adrs ADD COLUMN supersedes_accepted_id INTEGER REFERENCES adrs(id);
ALTER TABLE adrs ADD CONSTRAINT ck_adrs_supersedes_accepted
    CHECK (supersedes_accepted_id IS NULL OR supersedes_accepted_id IS NOT DISTINCT FROM supersedes_id);
UPDATE adrs
   SET supersedes_accepted_id = supersedes_id
 WHERE supersedes_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM adr_events e WHERE e.adr_id = adrs.id AND e.event_type = 'SUCCESSOR_ACTIVATED');
