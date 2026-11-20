// ADR API (`/api/v1/adrs`, 0.2.0) — the record, its lifecycle transitions, its DACI participants and its history.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema. The document PUT, the transitions
// and the participants PUT carry `X-Expected-Revision` (the revision the caller read); a real participant change bumps it.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type AdrStatus = components["schemas"]["AdrStatus"];
export type AdrRole = components["schemas"]["AdrRole"];
export type AdrParticipant = components["schemas"]["AdrParticipant"];
export type AdrParticipantRequest = components["schemas"]["AdrParticipantRequest"];
export type AdrResponse = components["schemas"]["AdrResponse"];
export type AdrPage = components["schemas"]["AdrPage"];
export type AdrEventResponse = components["schemas"]["AdrEventResponse"];
export type AdrEventPage = components["schemas"]["AdrEventPage"];
export type AdrCreateBody = paths["/api/v1/adrs"]["post"]["requestBody"]["content"]["application/json"];
export type AdrUpdateBody = paths["/api/v1/adrs/{id}"]["put"]["requestBody"]["content"]["application/json"];

const REVISION_HEADER = "X-Expected-Revision";

type AdrListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  /** Any-of; each status travels as its own repeated key. */
  status?: readonly AdrStatus[];
  q?: string;
  /** A user id: only the ADRs this user holds a role on. */
  participant?: number;
  /** Narrows `participant` to one DACI role (the server 400s a lone role). */
  role?: AdrRole;
};

export async function listAdrs(q: AdrListQuery): Promise<AdrPage> {
  const params = buildQuery({
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
    status: q.status,
    q: q.q,
    participant: q.participant,
    role: q.role,
  });
  return jsonRequest<AdrPage>(`/api/v1/adrs?${params}`);
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

/** One lifecycle move; answers the ADR as the caller now sees it. */
export async function transitionAdr(id: number, revision: number, to: AdrStatus): Promise<AdrResponse> {
  return jsonRequest<AdrResponse>(`/api/v1/adrs/${id}/transitions`, {
    method: "POST",
    headers: { [REVISION_HEADER]: String(revision) },
    body: JSON.stringify({ to }),
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

export async function deleteAdr(id: number): Promise<void> {
  await voidRequest(`/api/v1/adrs/${id}`, { method: "DELETE" });
}

export async function listAdrEvents(id: number, q: { page: number; pageSize: number; sort?: string }): Promise<AdrEventPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort });
  return jsonRequest<AdrEventPage>(`/api/v1/adrs/${id}/events?${params}`);
}
