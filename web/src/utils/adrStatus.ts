import type { MantineColor } from "@mantine/core";
import type { AdrRole, AdrStatus } from "../api/adrs";

/** The lifecycle in order (the server sorts by `status` in this order, not alphabetically). */
export const ADR_STATUSES: readonly AdrStatus[] = [
  "PROBLEM_RAISED",
  "SOLUTION_DRAFT",
  "IN_REVIEW",
  "ACTIVE",
  "CANCELLED",
  "WITHDRAWN",
  "EXPIRED",
  "SUPERSEDED",
];

/** The DACI roles in the order they are listed everywhere. */
export const ADR_ROLES: readonly AdrRole[] = ["DRIVER", "APPROVER", "CONTRIBUTOR", "INFORMED"];

/**
 * THE status colour map, used by the badge, the list and the history alike. It follows the app-wide colour vocabulary
 * (`web/CLAUDE.md`): gray = neutral state, grape = waiting, cyan = in progress, teal = success, orange = warning/waived, red = ended
 * hard — never stock blue, indigo or violet, which sit beside the lapis brand.
 */
export const ADR_STATUS_COLOR: Record<AdrStatus, MantineColor> = {
  PROBLEM_RAISED: "gray",
  SOLUTION_DRAFT: "grape",
  IN_REVIEW: "cyan",
  ACTIVE: "teal",
  CANCELLED: "gray",
  WITHDRAWN: "orange",
  EXPIRED: "red",
  SUPERSEDED: "gray",
};

/** Content (the name and the Markdown fields) is open to edits only before the decision is in force. */
const PRE_ACTIVE: ReadonlySet<AdrStatus> = new Set(["PROBLEM_RAISED", "SOLUTION_DRAFT", "IN_REVIEW"]);

export function isPreActive(status: AdrStatus): boolean {
  return PRE_ACTIVE.has(status);
}

/** Terminal statuses are read-only for everyone: the decision was cancelled, withdrawn, expired or superseded. */
export function isTerminal(status: AdrStatus): boolean {
  return !isPreActive(status) && status !== "ACTIVE";
}

/** Type guards for values that arrive as plain strings (URL params, event params). */
export const isAdrStatus = (value: string | null | undefined): value is AdrStatus => (ADR_STATUSES as readonly (string | null | undefined)[]).includes(value);
export const isAdrRole = (value: string | null | undefined): value is AdrRole => (ADR_ROLES as readonly (string | null | undefined)[]).includes(value);
