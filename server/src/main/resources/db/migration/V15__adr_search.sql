-- Full-text search over the ADRs and the indexes the list's remaining filters need (0.5.0, `.claude/docs/adr-model.md` "Search",
-- `persistence.md` "Search and filters (V15)"). Adder's V16 search column, ported and reshaped: ONE stored generated `tsvector` on `adrs`
-- and a GIN index (an ADR search matches the whole table, unlike Adder's per-thread probe, so the index is what serves it).
--
-- `public.unaccent()` is only STABLE, so it cannot feed a generated column or an expression index. PostgreSQL's own answer is a text
-- search configuration whose dictionary chain holds `unaccent` as a FILTERING dictionary: `to_tsvector(regconfig, text)` is
-- IMMUTABLE, and `ts_headline` with the same configuration marks a hit in the ORIGINAL text (accents kept). `hammurabi_search` copies
-- `simple` (no stemming, no stop words: ADR text is mixed English and Polish) and maps the six token types that can hold non-ASCII
-- letters through `unaccent` first, so "zolw" finds "Zolw" and "Zolw" finds "zolw". A non-superuser owner may create it (the V4
-- extension is already in `public`).
--
-- The weights: the name is A, the problem and the decision B, the context, the alternatives and the consequences C. `STORED` MUST be
-- written: on PostgreSQL 18 a bare `GENERATED ALWAYS AS (...)` is VIRTUAL (computed again on every read, and no index on it is
-- supported). The ALTER TABLE below IS the backfill: it rewrites `adrs` and computes every row, with the application not serving while
-- Flyway runs (an ADR log is thousands of rows at most, so this is milliseconds). A token of 2,047 BYTES or more is skipped with a
-- NOTICE and the INSERT still succeeds; the fields are capped at 20,000 characters (V10), far below the 1 MB limit of one tsvector.
--
-- The configuration is IMMUTABLE BY PROMISE. Altering it, or a major PostgreSQL upgrade that changes the `unaccent` rules, leaves the
-- stored vectors (and the GIN index) stale until they are recomputed: `ALTER TABLE adrs ALTER COLUMN search SET EXPRESSION AS (...)`
-- (the runbook is in `persistence.md`). The configuration cannot be dropped while the column depends on it.
--
-- The column is never declared in an Exposed table object (`selectAll()` would read a vector on every ADR read, and the driver has no
-- `tsvector` codec): `infra/db/Fts.kt` names the configuration and refers to the column as a free expression.
CREATE TEXT SEARCH CONFIGURATION public.hammurabi_search (COPY = pg_catalog.simple);
ALTER TEXT SEARCH CONFIGURATION public.hammurabi_search
    ALTER MAPPING FOR hword, hword_part, word, numword, numhword, hword_numpart
    WITH public.unaccent, pg_catalog.simple;

-- `pg_catalog.to_tsvector`, `setweight` schema-qualified: a generated column's expression is STORED PARSED (as function OIDs), so the
-- search_path matters only when the text is parsed: this migration, a later `SET EXPRESSION`, or a dump restore.
ALTER TABLE adrs ADD COLUMN search tsvector GENERATED ALWAYS AS (
    pg_catalog.setweight(pg_catalog.to_tsvector('public.hammurabi_search'::regconfig, "name"), 'A') ||
    pg_catalog.setweight(pg_catalog.to_tsvector('public.hammurabi_search'::regconfig, problem || ' ' || decision), 'B') ||
    pg_catalog.setweight(pg_catalog.to_tsvector('public.hammurabi_search'::regconfig, context || ' ' || alternatives || ' ' || consequences), 'C')
) STORED;
CREATE INDEX idx_adrs_search ON adrs USING GIN (search);

-- The remaining list filters: the created and updated ranges (`created_at`, `updated_at` are epoch millis; `updated_at` is also a sort
-- key) and "expires before" (any status: an EXPIRED ADR keeps its date). All partial on the rows that still exist, like `idx_adrs_status`.
CREATE INDEX idx_adrs_created_at ON adrs (created_at) WHERE NOT marked_as_deleted;
CREATE INDEX idx_adrs_updated_at ON adrs (updated_at) WHERE NOT marked_as_deleted;
CREATE INDEX idx_adrs_expires_on ON adrs (expires_on) WHERE expires_on IS NOT NULL AND NOT marked_as_deleted;
