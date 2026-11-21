-- 1.0.0 API freeze (`.claude/docs/adr-model.md` "Lenses", `persistence.md` "Lens filter names (V20)"): the ADR list's filters were renamed
-- (participant -> participantId, role -> participantRole, supersedes -> hasPredecessor, supersededBy -> hasSuccessor), and a lens stores the
-- list's own filter document, so the stored keys follow. Every row is rewritten, soft-deleted ones included (a lens can be restored by
-- nobody today, but a row left behind would read as an unfiltered lens). Only the four keys move: values keep their JSON types, other keys
-- stay, and a document without an old key is not touched at all (the WHERE), so it stays byte-identical. The column is TEXT, hence the casts.
-- A document that holds BOTH spellings (a lens saved by 0.9.x after a rollback) keeps the NEW key's value: jsonb_object_agg lets the last
-- duplicate win, so the old-spelled entries are ordered first.
-- Rollback to 0.9.x: STOP 1.0.0 first, then run the same statement reversed BEFORE starting the old release (the old spelling wins there):
--   UPDATE lenses SET filters = (SELECT jsonb_object_agg(CASE e.key WHEN 'participantId' THEN 'participant' WHEN 'participantRole' THEN 'role'
--       WHEN 'hasPredecessor' THEN 'supersedes' WHEN 'hasSuccessor' THEN 'supersededBy' ELSE e.key END, e.value
--       ORDER BY (e.key IN ('participantId', 'participantRole', 'hasPredecessor', 'hasSuccessor')) DESC)
--       FROM jsonb_each(lenses.filters::jsonb) AS e(key, value))::text
--     WHERE EXISTS (SELECT 1 FROM jsonb_object_keys(lenses.filters::jsonb) AS k(key)
--       WHERE k.key IN ('participantId', 'participantRole', 'hasPredecessor', 'hasSuccessor'));
-- Rolling forward again: Flyway will not re-run V20, so before starting 1.0.0 again run the forward statement below by hand (it is
-- idempotent: its WHERE skips every document already in the new spelling); lenses saved or reversed under 0.9.x would otherwise
-- silently widen (an unknown key is read leniently and dropped).
UPDATE lenses
   SET filters = (SELECT jsonb_object_agg(
                    CASE e.key WHEN 'participant' THEN 'participantId' WHEN 'role' THEN 'participantRole'
                               WHEN 'supersedes' THEN 'hasPredecessor' WHEN 'supersededBy' THEN 'hasSuccessor'
                               ELSE e.key END, e.value
                    ORDER BY (e.key IN ('participant', 'role', 'supersedes', 'supersededBy')) DESC)
                    FROM jsonb_each(lenses.filters::jsonb) AS e(key, value))::text
 WHERE EXISTS (SELECT 1 FROM jsonb_object_keys(lenses.filters::jsonb) AS k(key)
                WHERE k.key IN ('participant', 'role', 'supersedes', 'supersededBy'));
