-- 1.0.0 API freeze (`.claude/docs/adr-model.md` "Lenses", `persistence.md` "Lens filter names (V20)"): the ADR list's filters were renamed
-- (participant -> participantId, role -> participantRole, supersedes -> hasPredecessor, supersededBy -> hasSuccessor), and a lens stores the
-- list's own filter document, so the stored keys follow. Every row is rewritten, soft-deleted ones included (a lens can be restored by
-- nobody today, but a row left behind would read as an unfiltered lens). Only the four keys move: values keep their JSON types, other keys
-- stay, and a document without an old key is not touched at all (the WHERE), so it stays byte-identical. The column is TEXT, hence the casts.
-- Rollback to 0.9.x (the same statement reversed, run BEFORE starting the old release):
--   UPDATE lenses SET filters = (SELECT jsonb_object_agg(CASE e.key WHEN 'participantId' THEN 'participant' WHEN 'participantRole' THEN 'role'
--       WHEN 'hasPredecessor' THEN 'supersedes' WHEN 'hasSuccessor' THEN 'supersededBy' ELSE e.key END, e.value)
--       FROM jsonb_each(lenses.filters::jsonb) AS e(key, value))::text
--     WHERE EXISTS (SELECT 1 FROM jsonb_object_keys(lenses.filters::jsonb) AS k(key)
--       WHERE k.key IN ('participantId', 'participantRole', 'hasPredecessor', 'hasSuccessor'));
UPDATE lenses
   SET filters = (SELECT jsonb_object_agg(
                    CASE e.key WHEN 'participant' THEN 'participantId' WHEN 'role' THEN 'participantRole'
                               WHEN 'supersedes' THEN 'hasPredecessor' WHEN 'supersededBy' THEN 'hasSuccessor'
                               ELSE e.key END, e.value)
                    FROM jsonb_each(lenses.filters::jsonb) AS e(key, value))::text
 WHERE EXISTS (SELECT 1 FROM jsonb_object_keys(lenses.filters::jsonb) AS k(key)
                WHERE k.key IN ('participant', 'role', 'supersedes', 'supersededBy'));
