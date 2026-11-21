// Lenses API (`/api/v1/lenses`, 0.5.0) - named, saved ADR-list filters. A lens is PRIVATE to its creator or PUBLIC to every signed-in user and
// is changed only by its creator (an ADMIN may delete a PUBLIC one); the server's `canEdit` / `canDelete` say what the caller may do.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components } from "./schema";

export type Lens = components["schemas"]["Lens"];
export type LensBody = components["schemas"]["LensRequest"];
/** The ADR list's filters as one document: the keys ARE the list's query parameter names (`status` and `tag` as arrays, all optional). */
export type LensFilters = components["schemas"]["AdrFilterDocument"];
type LensPage = components["schemas"]["LensPage"];

type LensListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  /** `true`: only the caller's own lenses (at most 50); `false`: only the PUBLIC lenses of others. */
  mine?: boolean;
};

export async function listLenses(q: LensListQuery): Promise<LensPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, mine: q.mine });
  return jsonRequest<LensPage>(`/api/v1/lenses?${params}`);
}

export async function createLens(body: LensBody): Promise<Lens> {
  return jsonRequest<Lens>("/api/v1/lenses", { method: "POST", body: JSON.stringify(body) });
}

/** A whole-lens replace (rename, flip the visibility and overwrite the filters are all this one PUT), by its creator. */
export async function updateLens(id: number, body: LensBody): Promise<void> {
  await voidRequest(`/api/v1/lenses/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteLens(id: number): Promise<void> {
  await voidRequest(`/api/v1/lenses/${id}`, { method: "DELETE" });
}
