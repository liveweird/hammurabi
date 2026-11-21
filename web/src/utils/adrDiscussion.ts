import type { MantineColor } from "@mantine/core";
import type { DiscussionKind, DiscussionState } from "../api/adrDiscussion";

/** Comments and questions, then issues and escalations: the two sections of the ADR page, in the order the kinds are offered. */
export type DiscussionVariant = "discussion" | "issues";

export const DISCUSSION_VARIANT_KINDS: Record<DiscussionVariant, readonly DiscussionKind[]> = {
  discussion: ["COMMENT", "QUESTION"],
  issues: ["ISSUE", "ESCALATION"],
};

/** Type guard for a kind that arrives as a plain string (a notification's `detail`). */
export const isDiscussionKind = (value: string | null | undefined): value is DiscussionKind =>
  Object.values(DISCUSSION_VARIANT_KINDS).some((kinds) => (kinds as readonly (string | null | undefined)[]).includes(value));

/** The server's bounds (`MAX_DISCUSSION_BODY_LENGTH`, `MAX_DISCUSSION_RESOLUTION_LENGTH`). */
export const DISCUSSION_BODY_MAX = 10_000;
export const DISCUSSION_RESOLUTION_MAX = 2_000;

/** Twenty entries a page, the server's default; a thread reads oldest first, so a new post lands on the last page. */
export const DISCUSSION_PAGE_SIZE = 20;

/**
 * THE kind colour map (the app-wide vocabulary, `web/CLAUDE.md`): red = blocking (an escalation), orange = warning (an issue), grape =
 * waiting (a question), gray = neutral (a comment) — never stock blue, indigo or violet, which sit beside the lapis brand.
 */
export const DISCUSSION_KIND_COLOR: Record<DiscussionKind, MantineColor> = {
  COMMENT: "gray",
  QUESTION: "grape",
  ISSUE: "orange",
  ESCALATION: "red",
};

/** The state colour map: settled is teal (success), open stays neutral so the kind badge carries the urgency. */
export const DISCUSSION_STATE_COLOR: Record<DiscussionState, MantineColor> = {
  OPEN: "gray",
  ANSWERED: "teal",
  RESOLVED: "teal",
};

/** The last page a thread has once it holds [total] entries (an empty thread is page 1). */
export function lastDiscussionPage(total: number, pageSize: number = DISCUSSION_PAGE_SIZE): number {
  return Math.max(1, Math.ceil(total / pageSize));
}
