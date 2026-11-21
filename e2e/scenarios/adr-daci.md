# ADR roles (what each DACI role sees)

- **Spec**: [tests/adr-daci.spec.ts](../tests/adr-daci.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; three throwaway regular users — a Driver (raises the
  ADR through the API), a Contributor and an outsider with no role — who read it in the browser
- **Owns** (exclusive server-side state): its three throwaway users (unique `E2E Driver-*` / `E2E Contributor-*` / `E2E Outsider-*`
  names), the one ADR (unique `e2e-adr-*` name) the Driver raises with the Contributor named, and — for the expiry scenario — a fourth
  throwaway user (`E2E Approver-*`) and a second ADR the Driver raises with that Approver, taken to `ACTIVE` through the API — all
  deleted through the API after each test

## Scenario: a user who is not a participant reads the ADR but sees no controls

1. The outsider signs in and opens the ADR.
   - *Expected*: the name as the heading, the problem text, the Driver in the DACI section and "ADR raised" in the History are all visible.
2. They look for controls.
   - *Expected*: "No status change is available to you." and no **Edit**, **Move to draft**, **Send to review**, **Cancel ADR**,
     **Activate**, **Withdraw**, **Edit participants**, **Edit the expiry date** or **Delete** button.

## Scenario: a Contributor edits the content but cannot move the ADR or change who takes part

1. The Contributor signs in and opens the ADR.
   - *Expected*: they are marked **You** in the DACI section.
2. They click **Edit**, write the context and **Save**.
   - *Expected*: the context shows in its section and the History lists "Context changed".
3. They look for the other controls.
   - *Expected*: "No status change is available to you." and no **Move to draft**, **Send to review**, **Cancel ADR**,
     **Edit participants** or **Edit the expiry date** button.

## Scenario: the expiry date of a decision in force is the Approver's to change, and the Driver is told

1. The Driver raises an ADR with an Approver and its content through the API and sends it to review; the Approver activates it.
2. The Driver signs in and opens the ADR.
   - *Expected*: its status reads "Active" and there is no **Edit the expiry date** button.
3. The Approver signs in, opens the ADR, clicks **Edit the expiry date**, enters `2030-01-31` and **Save**s.
   - *Expected*: the date shows in the Details section.
4. The Driver signs in again and opens the bell.
   - *Expected*: a notice reads "<Approver> changed the expiry date of ADR-NNNN to 2030-01-31".

## Not covered here (and why)

- **The Approver's and the Driver's buttons** — `adr-lifecycle.spec.ts` drives them (the expiry editor of a decision in force is above).
- **The 403 matrix** — the server's `AdrAccessTest`; this spec asserts only what a person sees.
