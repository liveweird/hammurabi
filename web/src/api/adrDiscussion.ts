// ADR discussion API (`/api/v1/adrs/{id}/discussion`, 0.3.0): comments and questions before the decision is in force, issues and
// escalations while it is. Thin wrappers: transport in ./http, types from ./schema. No revision header: the discussion is not part
// of the ADR document, so a post never conflicts with an edit.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components } from "./schema";

export type DiscussionKind = components["schemas"]["DiscussionKind"];
export type DiscussionStatus = components["schemas"]["DiscussionStatus"];
export type DiscussionEntry = components["schemas"]["DiscussionEntry"];
export type DiscussionPage = components["schemas"]["DiscussionPage"];

type DiscussionQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  /** Any-of; each kind travels as its own repeated key. */
  kind?: readonly DiscussionKind[];
  status?: DiscussionStatus;
};

const base = (adrId: number) => `/api/v1/adrs/${adrId}/discussion`;

/** One page of an ADR's entries, oldest first unless `sort` says otherwise; a deleted entry is a tombstone. */
export async function listDiscussion(adrId: number, q: DiscussionQuery): Promise<DiscussionPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, kind: q.kind, status: q.status });
  return jsonRequest<DiscussionPage>(`${base(adrId)}?${params}`);
}

export async function postDiscussionEntry(adrId: number, kind: DiscussionKind, body: string): Promise<DiscussionEntry> {
  return jsonRequest<DiscussionEntry>(base(adrId), { method: "POST", body: JSON.stringify({ kind, body }) });
}

export async function editDiscussionEntry(adrId: number, entryId: number, body: string): Promise<DiscussionEntry> {
  return jsonRequest<DiscussionEntry>(`${base(adrId)}/${entryId}`, { method: "PUT", body: JSON.stringify({ body }) });
}

/** A soft delete: the entry stays as a tombstone. */
export async function deleteDiscussionEntry(adrId: number, entryId: number): Promise<void> {
  await voidRequest(`${base(adrId)}/${entryId}`, { method: "DELETE" });
}

/** Answers, resolves or reopens an entry (idempotent); the optional note rides a resolution only. */
export async function setDiscussionEntryState(
  adrId: number,
  entryId: number,
  status: DiscussionStatus,
  resolution?: string,
): Promise<DiscussionEntry> {
  return jsonRequest<DiscussionEntry>(`${base(adrId)}/${entryId}/state`, {
    method: "PUT",
    body: JSON.stringify(resolution ? { status, resolution } : { status }),
  });
}
