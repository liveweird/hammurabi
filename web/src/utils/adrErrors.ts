import type { ParseKeys, TFunction } from "i18next";
import { ApiError } from "../api/http";
import { saveErrorMessage } from "./saveError";

/** The typed 409 problems the ADR writes answer (`ProblemTypes.kt`); every other 409 is `about:blank` (a name clash). */
const REVISION_CONFLICT = "urn:hammurabi:problem:revision-conflict";
const ILLEGAL_TRANSITION = "urn:hammurabi:problem:illegal-transition";
const ADR_FROZEN = "urn:hammurabi:problem:adr-frozen";
const DISCUSSION_CLOSED = "urn:hammurabi:problem:discussion-closed";

/** What went stale under a write: the document moved on, the content froze, the lifecycle map refused the move, or the status closed the discussion. */
export type AdrConflict = "revision" | "frozen" | "illegalTransition" | "discussionClosed";

const CONFLICT_BY_TYPE: Record<string, AdrConflict> = {
  [REVISION_CONFLICT]: "revision",
  [ILLEGAL_TRANSITION]: "illegalTransition",
  [ADR_FROZEN]: "frozen",
  [DISCUSSION_CLOSED]: "discussionClosed",
};

/** The typed conflict of a failed write, or null (not a 409, or an untyped one such as a name clash). */
export function adrConflict(err: unknown): AdrConflict | null {
  return err instanceof ApiError && err.status === 409 ? (CONFLICT_BY_TYPE[err.type ?? ""] ?? null) : null;
}

/** Which write failed — each has its own 400 (and 409) meaning. */
export type AdrWriteKind = "create" | "save" | "transition" | "participants" | "discussion";

const INVALID: Record<AdrWriteKind, ParseKeys> = {
  create: "adrs.error.createInvalid",
  save: "adrs.error.saveInvalid",
  transition: "adrs.error.transitionInvalid",
  participants: "adrs.error.participantsInvalid",
  discussion: "adrs.discussion.error.invalid",
};

const CONFLICT_KEY: Record<AdrConflict, ParseKeys> = {
  revision: "adrs.error.revisionConflict",
  frozen: "adrs.error.frozen",
  illegalTransition: "adrs.error.illegalTransition",
  discussionClosed: "adrs.discussion.error.closed",
};

/** The fixed words of a failed ADR write — never the server's text. */
export function adrWriteError(err: unknown, t: TFunction, kind: AdrWriteKind): string {
  const conflict = adrConflict(err);
  if (conflict) return t(CONFLICT_KEY[conflict]);
  return saveErrorMessage(err, t, {
    forbidden: "adrs.error.forbidden",
    notFound: kind === "discussion" ? "adrs.discussion.error.gone" : "adrs.error.gone",
    conflict: "adrs.error.nameTaken",
    invalid: INVALID[kind],
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
