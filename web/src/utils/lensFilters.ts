import type { LensFilters } from "../api/lenses";
import { isAdrRole, isAdrStatus } from "./adrStatus";
import { DATE_KEYS, NO_ADR_FILTERS, searchTermOf, TRI_STATE_KEYS, type AdrListParams } from "./adrListParams";
import { isValidIsoDate } from "./isoDate";

/**
 * The URL filters as a lens document (what "Save current filters as lens" stores): the list's own parameter names, every absent filter
 * omitted. "My ADRs" travels as the signed-in user's `participant` id - a lens holds no `mine` - and a role only with a participant.
 */
export function filtersFromParams(params: AdrListParams, userId: number | null): LensFilters {
  const participant = params.participant ?? (params.mine && userId !== null ? userId : null);
  const filters: LensFilters = {};
  if (params.q.trim() !== "") filters.q = params.q.trim();
  if (params.text !== "") filters.text = params.text;
  if (params.status.length > 0) filters.status = params.status;
  if (params.tag.length > 0) filters.tag = params.tag;
  if (participant !== null) {
    filters.participant = participant;
    if (params.role) filters.role = params.role;
  }
  for (const key of DATE_KEYS) if (params[key] !== "") filters[key] = params[key];
  for (const key of TRI_STATE_KEYS) if (params[key] !== null) filters[key] = params[key];
  return filters;
}

/**
 * A lens document as URL filters: EVERY filter the lens lacks is reset (a lens replaces the filters, it never adds to them), and its own
 * participant is the signed-in user's `mine` when it is that user's id. Values the list would refuse are dropped as the URL reader does.
 */
export function paramsFromFilters(filters: LensFilters, userId: number | null): AdrListParams {
  const participant = filters.participant ?? null;
  const mine = participant !== null && participant === userId;
  return {
    ...NO_ADR_FILTERS,
    q: filters.q ?? "",
    text: searchTermOf(filters.text),
    status: [...new Set((filters.status ?? []).filter(isAdrStatus))],
    tag: [...new Set((filters.tag ?? []).filter((name) => name !== ""))],
    mine,
    participant: participant !== null && !mine ? participant : null,
    role: participant !== null && isAdrRole(filters.role) ? filters.role : null,
    createdFrom: validDay(filters.createdFrom),
    createdTo: validDay(filters.createdTo),
    updatedFrom: validDay(filters.updatedFrom),
    updatedTo: validDay(filters.updatedTo),
    expiresBefore: validDay(filters.expiresBefore),
    hasOpenIssues: filters.hasOpenIssues ?? null,
    supersedes: filters.supersedes ?? null,
    supersededBy: filters.supersededBy ?? null,
  };
}

function validDay(value: string | undefined): string {
  return value !== undefined && isValidIsoDate(value) ? value : "";
}

/** Whether two lens documents filter the same way (order of statuses and tags, blank values and a role without a participant do not count). */
export function sameFilters(a: LensFilters, b: LensFilters): boolean {
  return canonical(a) === canonical(b);
}

function canonical(filters: LensFilters): string {
  const participant = filters.participant ?? null;
  return JSON.stringify({
    q: (filters.q ?? "").trim(),
    text: (filters.text ?? "").trim(),
    status: [...new Set(filters.status ?? [])].sort(),
    tag: [...new Set(filters.tag ?? [])].sort(),
    participant,
    role: participant === null ? null : (filters.role ?? null),
    createdFrom: filters.createdFrom ?? "",
    createdTo: filters.createdTo ?? "",
    updatedFrom: filters.updatedFrom ?? "",
    updatedTo: filters.updatedTo ?? "",
    expiresBefore: filters.expiresBefore ?? "",
    hasOpenIssues: filters.hasOpenIssues ?? null,
    supersedes: filters.supersedes ?? null,
    supersededBy: filters.supersededBy ?? null,
  });
}

/** Whether the document filters on anything (an empty one is not worth saving or highlighting). */
export function hasFilters(filters: LensFilters): boolean {
  return canonical(filters) !== canonical({});
}
