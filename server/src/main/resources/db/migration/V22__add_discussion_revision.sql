-- Per-entry optimistic concurrency. The discussion is outside the ADR document revision, so its editable rows carry their own token.
ALTER TABLE adr_discussion ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
