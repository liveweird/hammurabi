# ADR roles (what each DACI role sees)

- **Spec**: [tests/adr-daci.spec.ts](../tests/adr-daci.spec.ts)
- **Actors**: the seed administrator, only through the API to create and delete the cast; three throwaway regular users — a Driver (raises the
  ADR through the API), a Contributor and an outsider with no role — who read it in the browser
- **Owns** (exclusive server-side state): its three throwaway users (unique `E2E Driver-*` / `E2E Contributor-*` / `E2E Outsider-*`
  names) and the one ADR (unique `e2e-adr-*` name) the Driver raises with the Contributor named — all deleted through the API after each test

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

## Not covered here (and why)

- **The Approver's and the Driver's buttons** — `adr-lifecycle.spec.ts` drives them.
- **The 403 matrix** — the server's `AdrAccessTest`; this spec asserts only what a person sees.
