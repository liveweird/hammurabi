import type { AdrResponse, AdrRole } from "../api/adrs";
import { isPreActive } from "./adrStatus";

/** The caller's DACI role on this ADR, null when they do not take part. */
function roleOf(adr: AdrResponse, userId: number | null): AdrRole | null {
  return adr.participants.find((participant) => participant.userId === userId)?.role ?? null;
}

/**
 * Whether to offer the content editor (the name and the five Markdown fields). The server serves `canEdit` as "content OR expiry date",
 * which cannot tell an Approver (expiry only) from a Driver, so the role narrows it here — UX only, the server re-judges every write (403).
 * The content freeze (`ACTIVE` and the terminal states) is the status test.
 */
export function canEditContent(adr: AdrResponse, userId: number | null, admin: boolean): boolean {
  const role = roleOf(adr, userId);
  return adr.canEdit && isPreActive(adr.status) && (admin || role === "DRIVER" || role === "CONTRIBUTOR");
}

/** What entering `IN_REVIEW` (and `ACTIVE`) still lacks — the Markdown fields and the Approver; a hint, the server judges. */
export type ReviewRequirement = "context" | "decision" | "consequences" | "approver";

export function missingForReview(adr: AdrResponse): ReviewRequirement[] {
  const missing: ReviewRequirement[] = [];
  if (adr.context.trim() === "") missing.push("context");
  if (adr.decision.trim() === "") missing.push("decision");
  if (adr.consequences.trim() === "") missing.push("consequences");
  if (!adr.participants.some((participant) => participant.role === "APPROVER")) missing.push("approver");
  return missing;
}
