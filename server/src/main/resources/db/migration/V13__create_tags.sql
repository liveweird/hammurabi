-- ADR tags (0.4.0, `.claude/docs/adr-model.md` "Tags"; Toadie's `tag_categories`, ported WITHOUT the Backstage entity kinds and with the tags
-- as rows instead of a JSON array): an ADMIN-curated registry of CATEGORIES, each holding TAGS, and `adr_tags` joining ADRs to tags.
--
-- `tag_categories`: a name (unique among ACTIVE rows, case-insensitively - a soft-deleted category frees its name, the V6 `teams` rule) and an
-- optional description; `revision` is the optimistic-concurrency token of the category's PUT and DELETE (`X-Expected-Revision`, bumped by every
-- PUT, so an admin editing a stale copy gets a 409 instead of silently overwriting another admin's tag list). `tags`: one row per tag, in exactly ONE category (`category_id`). `name` is stored NORMALISED (trimmed, whitespace
-- runs collapsed, lower-cased - `tags/TagCategory.kt`), and `uq_tags_name` makes it unique over EVERY row, deleted ones too, on purpose: a
-- tag removed from the registry is soft-deleted (RETIRED), never hard-deleted, so the ADRs that carry it keep a real row to point at; adding
-- the same name to a category again REVIVES that row (same id, so those ADRs show it live again) instead of minting a twin. A tag is
-- therefore live exactly while `NOT marked_as_deleted`; deleting a category retires its tags with it. "Each tag in exactly one ACTIVE
-- category" is this one row plus the unique name; the registry writers also serialise on a table lock (`infra/db/Locking.kt`).
--
-- `adr_tags`: the ADR <-> tag join, a hard-delete join like `adr_participants` (a current fact; every change is an `adr_events` row).
-- `tag_id` has no ON DELETE rule: tags are only ever soft-deleted. A retired tag stays on the ADRs that carry it and is shown as
-- retired until the next tag write drops it (a write still carrying a retired tag is a 400). `added_by` is the user whose write added it.
CREATE TABLE tag_categories (
    id SERIAL PRIMARY KEY,
    "name" VARCHAR(60) NOT NULL,
    description VARCHAR(500),
    revision BIGINT NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_tag_categories_name_active ON tag_categories (LOWER("name")) WHERE NOT marked_as_deleted;

CREATE TABLE tags (
    id SERIAL PRIMARY KEY,
    category_id INTEGER NOT NULL REFERENCES tag_categories(id),
    "name" VARCHAR(40) NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_tags_name ON tags ("name");
CREATE INDEX idx_tags_category ON tags (category_id) WHERE NOT marked_as_deleted;

CREATE TABLE adr_tags (
    adr_id INTEGER NOT NULL REFERENCES adrs(id) ON DELETE CASCADE,
    tag_id INTEGER NOT NULL REFERENCES tags(id),
    added_at BIGINT NOT NULL,
    added_by BIGINT NOT NULL REFERENCES users(id),
    PRIMARY KEY (adr_id, tag_id)
);
-- The list's `tag` filter (any-of): the ADRs that carry a given tag.
CREATE INDEX idx_adr_tags_tag ON adr_tags (tag_id, adr_id);

-- The starter registry (documented in `adr-model.md` "Tags"): two small categories an ADMIN can rename, extend or delete.
INSERT INTO tag_categories ("name", description, created_at, updated_at)
VALUES
    ('Domain', 'The technical area a decision belongs to', (extract(epoch FROM now()) * 1000)::bigint, (extract(epoch FROM now()) * 1000)::bigint),
    ('Scope', 'How far a decision reaches', (extract(epoch FROM now()) * 1000)::bigint, (extract(epoch FROM now()) * 1000)::bigint);

INSERT INTO tags (category_id, "name")
SELECT c.id, t.tag
FROM (VALUES
    ('Domain', 'data'), ('Domain', 'security'), ('Domain', 'infrastructure'), ('Domain', 'frontend'), ('Domain', 'integration'),
    ('Scope', 'team'), ('Scope', 'system'), ('Scope', 'organisation')
) AS t(category, tag)
JOIN tag_categories c ON c."name" = t.category;
