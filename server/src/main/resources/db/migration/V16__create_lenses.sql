-- Lenses (0.5.0, `.claude/docs/adr-model.md` "Lenses"): named, saved ADR-list filters (Toadie's V20, ported). The filter document is one
-- JSON object in TEXT (the same shape as the list's query parameters, validated server-side against the SAME rules before it is stored
-- and replaced whole on every save), owned by its creator with a PRIVATE/PUBLIC visibility: a PRIVATE lens is visible only to its
-- creator, a PUBLIC one to every authenticated user but mutable by its creator only (an ADMIN may delete a PUBLIC one, nothing else).
-- `visibility` has no CHECK: the Kotlin enum is the whitelist (the V5 rule).
CREATE TABLE lenses (
    id SERIAL PRIMARY KEY,
    "name" VARCHAR(100) NOT NULL,
    visibility VARCHAR(10) NOT NULL,
    filters TEXT NOT NULL CHECK (char_length(filters) <= 20000),
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- The name is unique PER OWNER among active lenses (case-insensitive); a soft-deleted lens frees its name. Public lenses of different
-- creators may share a name: the picker shows the creator beside it.
CREATE UNIQUE INDEX uq_lenses_owner_name_active ON lenses (created_by, LOWER("name")) WHERE NOT marked_as_deleted;
-- The list reads "my lenses plus everyone's PUBLIC ones".
CREATE INDEX idx_lenses_public ON lenses (visibility) WHERE NOT marked_as_deleted;
