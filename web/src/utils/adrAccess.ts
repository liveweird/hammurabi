import type { AdrResponse } from "../api/adrs";

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
