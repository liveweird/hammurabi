import type { AdrRole, AdrStatus } from "../api/adrs";
import { isAdrRole, isAdrStatus } from "./adrStatus";

/** The ADR list's filters as the URL spells them: `q`, repeated `status`, repeated `tag`, `mine=1` and `role` (only meaningful with `mine`). */
export type AdrListParams = {
  q: string;
  status: AdrStatus[];
  /** Tag names, any-of. */
  tag: string[];
  mine: boolean;
  role: AdrRole | null;
};

/** Reads the filters, ignoring anything the list does not know (a stale or hand-edited link falls back to "no filter"). */
export function parseAdrListParams(search: URLSearchParams): AdrListParams {
  const mine = search.get("mine") === "1";
  const role = search.get("role");
  return {
    q: search.get("q") ?? "",
    status: [...new Set(search.getAll("status").filter(isAdrStatus))],
    tag: [...new Set(search.getAll("tag").filter((name) => name !== ""))],
    mine,
    role: mine && isAdrRole(role) ? role : null,
  };
}

/** Lays `patch` over the filters in `prev` and returns the new query string state; defaults leave no key behind. */
export function writeAdrListParams(prev: URLSearchParams, patch: Partial<AdrListParams>): URLSearchParams {
  const next = { ...parseAdrListParams(prev), ...patch };
  const search = new URLSearchParams(prev);
  for (const key of ["q", "status", "tag", "mine", "role"]) search.delete(key);
  if (next.q !== "") search.set("q", next.q);
  for (const status of next.status) search.append("status", status);
  for (const tag of next.tag) search.append("tag", tag);
  if (next.mine) {
    search.set("mine", "1");
    if (next.role) search.set("role", next.role);
  }
  return search;
}

/** How many of the panel's filters are active (the badge on the Filters button). */
export function activeAdrFilterCount(params: AdrListParams): number {
  return (params.q.trim() ? 1 : 0) + (params.status.length > 0 ? 1 : 0) + (params.tag.length > 0 ? 1 : 0) + (params.mine ? 1 : 0);
}
