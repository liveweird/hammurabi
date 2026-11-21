# Lenses (saved ADR-list filters)

- **Spec**: [tests/lenses.spec.ts](../tests/lenses.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; two throwaway regular users — a lens owner (who also
  raises the ADRs through the API) and another user
- **Owns** (exclusive server-side state): its two throwaway users (unique `E2E Lens Owner-*` / `E2E Lens Other-*` names), the lenses they save
  (unique `e2e-lens-*` names, deleted through their own API sessions because a PRIVATE lens is invisible to the admin) and the ADRs (unique
  `e2e-adr-lens-*` names) — all removed after each test, the lenses and the ADRs first

## Scenario: a Driver saves the current filters as a lens, applies it from a clean list, renames it and deletes it

1. The owner raises two ADRs sharing a unique name prefix through the API; one is moved to **Solution draft**.
2. They open the list filtered by the prefix and the draft status; only the draft is listed. From **Lens actions** they choose **Save current
   filters as lens…**, name it and press **Save**.
   - *Expected*: the dialog closes and the **Lens** box shows the new lens.
3. They open the list filtered by the prefix alone (both ADRs listed) and pick the lens in the **Lens** box.
   - *Expected*: `status=SOLUTION_DRAFT` is in the URL and only the draft is listed.
4. From **Lens actions** they choose **Rename / change visibility…**, give it a new name, pick **Shared** and press **Save**.
   - *Expected*: the dialog closes and the **Lens** box shows the new name.
5. From **Lens actions** they choose **Delete lens…** and confirm with **Delete**.
   - *Expected*: the dialog closes and the API lists no lens of theirs.

## Scenario: a PUBLIC lens is offered to another user and a PRIVATE one is not, and the other user cannot change it

1. The owner saves a PUBLIC and a PRIVATE lens through the API.
2. The other user opens the list and the **Lens** box.
   - *Expected*: the public lens is offered as "<name> — <owner>"; the private one is not. Choosing the public lens puts `status=SOLUTION_DRAFT` in the URL.
3. They open **Lens actions**.
   - *Expected*: "Save current filters as lens…" is offered; "Rename / change visibility…" and "Delete lens…" are not.
4. Through the API they try to change and delete the public lens, and to change and read the private one.
   - *Expected*: 403, 403, 404, 404.

## Not covered here (and why)

- **The verdict order, the 50-lens cap, name clashes, validation of the filters, the ADMIN's moderation delete** — pinned by `LensTest` (server).
