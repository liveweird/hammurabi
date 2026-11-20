-- The ADR aggregate (0.2.0, `.claude/docs/adr-model.md`): the record and its DACI participants. History is V11.
--
-- `adrs`: soft delete (`marked_as_deleted`, ADMIN only) - a deleted ADR stops reading as active and frees its name but KEEPS its
-- number (the counter below is `MAX("number") + 1` over ALL rows, never reused). `"number"` is ONE global gapless counter allocated
-- under a transaction-scoped advisory lock (`adrs/AdrService.kt`, `persistence.md` "ADRs (V10)"); `uq_adrs_number` is the
-- backstop that turns a lost race into a failed insert instead of a duplicate. The wire key is `ADR-` + the number zero-padded to 4.
-- `name` is unique among ACTIVE rows, case- and accent-insensitively, through `adr_name_key` (an expression index cannot call the
-- STABLE `public.unaccent`, so a wrapper declared IMMUTABLE carries it - immutable BY PROMISE: a major PostgreSQL upgrade that changes
-- the unaccent rules needs a REINDEX of `uq_adrs_name_active`, the way Adder's search configuration does). `status` is the lifecycle
-- (`adrs/AdrLifecycle.kt`); no CHECK on enum-valued columns, the Kotlin enum is the whitelist (the V5 rule). `expires_on` is a
-- canonical ISO date text (`YYYY-MM-DD`, Covenant's date shape: it sorts chronologically and needs no date-time dependency), the day
-- the decision is last in force: the sweep expires an ACTIVE ADR whose date is BEFORE today (UTC). `revision` is the
-- optimistic-concurrency token of the document PUT, the transitions and the participants PUT (`X-Expected-Revision`); every real change bumps it.
-- The Markdown fields are bounded here too (20,000 characters), a backstop for the validators.
CREATE FUNCTION public.adr_name_key(name text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT lower(public.unaccent('public.unaccent'::regdictionary, name)) $$;

CREATE TABLE adrs (
    id SERIAL PRIMARY KEY,
    "number" INTEGER NOT NULL CHECK ("number" > 0),
    "name" VARCHAR(120) NOT NULL,
    problem TEXT NOT NULL CHECK (char_length(problem) <= 20000),
    context TEXT NOT NULL DEFAULT '' CHECK (char_length(context) <= 20000),
    decision TEXT NOT NULL DEFAULT '' CHECK (char_length(decision) <= 20000),
    alternatives TEXT NOT NULL DEFAULT '' CHECK (char_length(alternatives) <= 20000),
    consequences TEXT NOT NULL DEFAULT '' CHECK (char_length(consequences) <= 20000),
    status VARCHAR(20) NOT NULL,
    expires_on VARCHAR(10) CHECK (expires_on ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    status_changed_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_adrs_number ON adrs ("number");
CREATE UNIQUE INDEX uq_adrs_name_active ON adrs (public.adr_name_key("name")) WHERE NOT marked_as_deleted;
CREATE INDEX idx_adrs_status ON adrs (status) WHERE NOT marked_as_deleted;
-- The expiry sweep's candidate scan (`adrs/AdrExpirySweep.kt`).
CREATE INDEX idx_adrs_expiry ON adrs (expires_on) WHERE status = 'ACTIVE' AND expires_on IS NOT NULL AND NOT marked_as_deleted;

-- `adr_participants`: the DACI roles, a hard-delete join (a participant is a current fact, not history: every change is an
-- `adr_events` row) replaced through a diff. One role per user per ADR (the primary key); exactly one DRIVER is kept by the service
-- and at most ONE of each of DRIVER and APPROVER is pinned here by partial unique indexes. `user_id` may name a user soft-deleted
-- since: readers resolve the name through `users` regardless. `added_by` is the user whose write created the row.
CREATE TABLE adr_participants (
    adr_id INTEGER NOT NULL REFERENCES adrs(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    role VARCHAR(20) NOT NULL,
    added_at BIGINT NOT NULL,
    added_by BIGINT NOT NULL REFERENCES users(id),
    PRIMARY KEY (adr_id, user_id)
);
CREATE UNIQUE INDEX uq_adr_participants_driver ON adr_participants (adr_id) WHERE role = 'DRIVER';
CREATE UNIQUE INDEX uq_adr_participants_approver ON adr_participants (adr_id) WHERE role = 'APPROVER';
-- "my ADRs" and the list's `participant` filter.
CREATE INDEX idx_adr_participants_user ON adr_participants (user_id, role);
