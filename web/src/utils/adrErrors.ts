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
export type AdrWriteKind = "create" | "save" | "transition" | "participants" | "tags" | "supersedes" | "detach" | "discussion";

/** The 400 of each write; a detach has no body to be invalid, so it has none. */
const INVALID: Record<Exclude<AdrWriteKind, "detach">, ParseKeys> = {
  create: "adrs.error.createInvalid",
  save: "adrs.error.saveInvalid",
  transition: "adrs.error.transitionInvalid",
  participants: "adrs.error.participantsInvalid",
  tags: "adrs.error.tagsInvalid",
  supersedes: "adrs.error.supersedesInvalid",
  discussion: "adrs.discussion.error.invalid",
};

/** The 404 of each write: the ADR is gone - or, for a detach, the pending successor is. */
const NOT_FOUND: Record<AdrWriteKind, ParseKeys> = {
  create: "adrs.error.gone",
  save: "adrs.error.gone",
  transition: "adrs.error.gone",
  participants: "adrs.error.gone",
  tags: "adrs.error.gone",
  supersedes: "adrs.error.gone",
  detach: "adrs.error.detachGone",
  discussion: "adrs.discussion.error.gone",
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
    forbidden: kind === "participants" ? "adrs.error.participantsForbidden" : "adrs.error.forbidden",
    notFound: NOT_FOUND[kind],
    // The untyped 409s: a name clash (create, save) or a successor link that closes a cycle / meets a rival successor.
    conflict: kind === "supersedes" ? "adrs.error.supersedesConflict" : "adrs.error.nameTaken",
    invalid: kind === "detach" ? undefined : INVALID[kind],
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
