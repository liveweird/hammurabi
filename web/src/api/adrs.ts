// ADR API (`/api/v1/adrs`, 0.2.0) — the record, its lifecycle transitions, its DACI participants, its tags, its successor link and its history.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema. The document PUT, the transitions
// the participants PUT, the tags PUT and the supersedes PUT carry `X-Expected-Revision` (the revision the caller read); a real change bumps it.

import { downloadRequest, type Download } from "./download";
import { buildQuery, jsonRequest, REVISION_HEADER, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type AdrStatus = components["schemas"]["AdrStatus"];
export type AdrRole = components["schemas"]["AdrRole"];
export type AdrParticipant = components["schemas"]["AdrParticipant"];
export type AdrParticipantRequest = components["schemas"]["AdrParticipantRequest"];
export type AdrTag = components["schemas"]["AdrTag"];
export type AdrRef = components["schemas"]["AdrRef"];
export type AdrResponse = components["schemas"]["AdrResponse"];
export type AdrPage = components["schemas"]["AdrPage"];
/** One run of a full-text match (the name or a body snippet): plain text, never markup, `hit` when it matched the search. */
export type SnippetSegment = components["schemas"]["SnippetSegment"];
/** The decision log (0.9.0): counts, what is in force by tag, recently decided, expiring soon and awaiting a decision - every section capped, with `more`. */
export type AdrOverview = components["schemas"]["AdrOverview"];
export type AdrLogItem = components["schemas"]["AdrLogItem"];
export type AdrEventResponse = components["schemas"]["AdrEventResponse"];
export type AdrEventPage = components["schemas"]["AdrEventPage"];
export type AdrCreateBody = paths["/api/v1/adrs"]["post"]["requestBody"]["content"]["application/json"];
export type AdrUpdateBody = paths["/api/v1/adrs/{id}"]["put"]["requestBody"]["content"]["application/json"];

/** The list's filters: the list takes them with its paging, the export (`exportAdrs`) alone. */
export type AdrFilterQuery = {
  /** Any-of; each status travels as its own repeated key. */
  status?: readonly AdrStatus[];
  q?: string;
  /** A user id: only the ADRs this user holds a role on. */
  participantId?: number;
  /** Narrows `participantId` to one DACI role (the server 400s a lone role). */
  participantRole?: AdrRole;
  /** Any-of; each tag name travels as its own repeated key. */
  tag?: readonly string[];
  /** FULL-TEXT search (2 to 100 characters trimmed; the server caps `pageSize` at 20 with it and ranks by relevance unless a sort is sent). */
  text?: string;
  /** UTC days, `YYYY-MM-DD`: inclusive bounds on the creation and last-change times. */
  createdFrom?: string;
  createdTo?: string;
  updatedFrom?: string;
  updatedTo?: string;
  /** A UTC day: the ADRs whose expiry date is strictly before it. */
  expiresBefore?: string;
  /** `true`/`false` each narrow; absent is "any" (so `false` IS sent). */
  hasOpenIssues?: boolean;
  hasPredecessor?: boolean;
  hasSuccessor?: boolean;
};

type AdrListQuery = AdrFilterQuery & { page: number; pageSize: number; sort?: string };

/** The filter parameters in the list's own names (the export takes exactly these). */
function filterParams(q: AdrFilterQuery) {
  return {
    status: q.status,
    q: q.q,
    participantId: q.participantId,
    participantRole: q.participantRole,
    tag: q.tag,
    text: q.text,
    createdFrom: q.createdFrom,
    createdTo: q.createdTo,
    updatedFrom: q.updatedFrom,
    updatedTo: q.updatedTo,
    expiresBefore: q.expiresBefore,
    hasOpenIssues: q.hasOpenIssues,
    hasPredecessor: q.hasPredecessor,
    hasSuccessor: q.hasSuccessor,
  };
}

export async function listAdrs(q: AdrListQuery): Promise<AdrPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, ...filterParams(q) });
  return jsonRequest<AdrPage>(`/api/v1/adrs?${params}`);
}

/** The ADR as one Markdown file (any signed-in user); the server names it `ADR-0042-<slug>.md`. */
export async function exportAdr(id: number): Promise<Download> {
  return downloadRequest(`/api/v1/adrs/${id}/export`, `adr-${id}.md`);
}

/** A ZIP of the Markdown files of every ADR the filters match (at most 500, else the server answers 400) plus an `index.md`. */
export async function exportAdrs(filters: AdrFilterQuery): Promise<Download> {
  return downloadRequest(`/api/v1/adrs/export?${buildQuery(filterParams(filters))}`, "adr-export.zip");
}

/** The decision log in one read (any signed-in user): no parameters, every section capped by the server. */
export async function getAdrOverview(): Promise<AdrOverview> {
  return jsonRequest<AdrOverview>("/api/v1/adrs/overview");
}

export async function getAdr(id: number): Promise<AdrResponse> {
  return jsonRequest<AdrResponse>(`/api/v1/adrs/${id}`);
}

export async function createAdr(body: AdrCreateBody): Promise<AdrResponse> {
  return jsonRequest<AdrResponse>("/api/v1/adrs", { method: "POST", body: JSON.stringify(body) });
}

/** The document PUT — a full replace under the revision the caller read. */
export async function updateAdr(id: number, revision: number, body: AdrUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/adrs/${id}`, {
    method: "PUT",
    headers: { [REVISION_HEADER]: String(revision) },
    body: JSON.stringify(body),
  });
}

/** One lifecycle move with an optional `reason` (kept in the history, never in a notice); answers the ADR as the caller now sees it. */
export async function transitionAdr(id: number, revision: number, to: AdrStatus, reason?: string): Promise<AdrResponse> {
  return jsonRequest<AdrResponse>(`/api/v1/adrs/${id}/transitions`, {
    method: "POST",
    headers: { [REVISION_HEADER]: String(revision) },
    body: JSON.stringify(reason ? { to, reason } : { to }),
  });
}

/** A wholesale replace of the DACI set (exactly one Driver, at most one Approver) under the revision the caller read. */
export async function replaceAdrParticipants(id: number, revision: number, participants: AdrParticipantRequest[]): Promise<void> {
  await voidRequest(`/api/v1/adrs/${id}/participants`, {
    method: "PUT",
    headers: { [REVISION_HEADER]: String(revision) },
    body: JSON.stringify({ participants }),
  });
}

/** A wholesale replace of the tag set (registry names; the server answers 400 for an unregistered or retired one) under the revision the caller read. */
export async function replaceAdrTags(id: number, revision: number, tags: string[]): Promise<void> {
  await voidRequest(`/api/v1/adrs/${id}/tags`, {
    method: "PUT",
    headers: { [REVISION_HEADER]: String(revision) },
    body: JSON.stringify({ tags }),
  });
}

/** Sets (or, with null, removes) the ADR this one replaces, under the revision the caller read. */
export async function setAdrSupersedes(id: number, revision: number, supersedesId: number | null): Promise<void> {
  await voidRequest(`/api/v1/adrs/${id}/supersedes`, {
    method: "PUT",
    headers: { [REVISION_HEADER]: String(revision) },
    body: JSON.stringify({ supersedesId }),
  });
}

/**
 * The PREDECESSOR's owner detaches the pending successor the caller saw (no revision header). Naming it protects a stale confirmation
 * from detaching a replacement link; the successor's revision bumps, while this ADR's own revision does not move.
 */
export async function detachAdrSuccessor(id: number, successorId: number): Promise<void> {
  await voidRequest(`/api/v1/adrs/${id}/successor?${buildQuery({ successorId })}`, { method: "DELETE" });
}

/**
 * The PREDECESSOR's Approver (or an administrator) accepts its pending successor, which the successor's activation needs while this
 * decision is in force. It names the successor so a link re-pointed since the page loaded is never accepted blind; no revision header (the
 * server bumps no revision for it).
 */
export async function acceptAdrSuccessor(id: number, successorId: number): Promise<void> {
  await voidRequest(`/api/v1/adrs/${id}/successor/accept`, { method: "POST", body: JSON.stringify({ successorId }) });
}

export async function deleteAdr(id: number): Promise<void> {
  await voidRequest(`/api/v1/adrs/${id}`, { method: "DELETE" });
}

export async function listAdrEvents(id: number, q: { page: number; pageSize: number; sort?: string }): Promise<AdrEventPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort });
  return jsonRequest<AdrEventPage>(`/api/v1/adrs/${id}/events?${params}`);
}
