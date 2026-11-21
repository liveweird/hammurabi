import type { AdrRole, AdrStatus } from "../api/adrs";
import { adrsPath } from "./adrLinks";
import { isAdrRole, isAdrStatus } from "./adrStatus";
import { isValidIsoDate } from "./isoDate";

/** The server's page-size cap while a full-text search runs (`GET /adrs?text=` is `400` above it). */
export const SEARCH_MAX_PAGE_SIZE = 20;

/** The shortest full-text term the server accepts after trimming, in code points (a single one is `400`). */
const MIN_SEARCH_LENGTH = 2;

/** A yes/no filter: `null` is "any" (the key is absent). */
export type TriState = boolean | null;

/**
 * The ADR list's filters as the URL spells them. The keys ARE the list endpoint's parameter names, except `mine=1` (the signed-in user's own
 * `participant`, the shortcut the panel's switch writes) - `participant` itself is only ever another user's id, which a shared lens can carry.
 */
export type AdrListParams = {
  /** The name-or-key substring. */
  q: string;
  /** The full-text term, trimmed: empty or a term the server accepts, see `searchTermOf` (anything else is read as no search). */
  text: string;
  status: AdrStatus[];
  /** Tag names, any-of. */
  tag: string[];
  mine: boolean;
  /** Another user's id (from an applied lens); `mine` is the signed-in user's. */
  participant: number | null;
  /** Only meaningful with `mine` or `participant`. */
  role: AdrRole | null;
  /** `YYYY-MM-DD` UTC days, "" = none. */
  createdFrom: string;
  createdTo: string;
  updatedFrom: string;
  updatedTo: string;
  expiresBefore: string;
  hasOpenIssues: TriState;
  supersedes: TriState;
  supersededBy: TriState;
};

export const DATE_KEYS = ["createdFrom", "createdTo", "updatedFrom", "updatedTo", "expiresBefore"] as const;
export const TRI_STATE_KEYS = ["hasOpenIssues", "supersedes", "supersededBy"] as const;

/** Every key this module owns in the query string; anything else (foreign keys) is left alone. */
const OWNED_KEYS = ["q", "text", "status", "tag", "mine", "participant", "role", ...DATE_KEYS, ...TRI_STATE_KEYS];

/** No filter at all - what "clear all" and a lens without a key write. */
export const NO_ADR_FILTERS: AdrListParams = {
  q: "",
  text: "",
  status: [],
  tag: [],
  mine: false,
  participant: null,
  role: null,
  createdFrom: "",
  createdTo: "",
  updatedFrom: "",
  updatedTo: "",
  expiresBefore: "",
  hasOpenIssues: null,
  supersedes: null,
  supersededBy: null,
};

function readTriState(search: URLSearchParams, key: string): TriState {
  const value = search.get(key);
  return value === "true" ? true : value === "false" ? false : null;
}

function readDate(search: URLSearchParams, key: string): string {
  const value = search.get(key) ?? "";
  return isValidIsoDate(value) ? value : "";
}

/** The server's letter-or-digit rule (`Character.isLetterOrDigit`: any letter, a DECIMAL digit): `!!` and `--` have no lexeme and are a `400`. */
const LETTER_OR_DIGIT = /[\p{L}\p{Nd}]/u;

/** Whether `term` (already trimmed) is a search the server accepts: two code points at least (not UTF-16 units) and a letter or digit. */
function isSearchable(term: string): boolean {
  return [...term].length >= MIN_SEARCH_LENGTH && LETTER_OR_DIGIT.test(term);
}

/** The committed full-text term: trimmed, and one the server would refuse (a lone character, no letter or digit) is no search at all. */
export function searchTermOf(raw: string | null | undefined): string {
  const term = (raw ?? "").trim();
  return isSearchable(term) ? term : "";
}

/** Text is typed in the search box but is not (yet) a term the server takes: the box hints at it. */
export function isUnsearchable(raw: string): boolean {
  const term = raw.trim();
  return term !== "" && !isSearchable(term);
}

function readParticipant(search: URLSearchParams): number | null {
  const raw = search.get("participant") ?? "";
  return /^\d+$/.test(raw) && Number(raw) > 0 ? Number(raw) : null;
}

/** Reads the filters, ignoring anything the list does not know (a stale or hand-edited link falls back to "no filter"). */
export function parseAdrListParams(search: URLSearchParams): AdrListParams {
  const mine = search.get("mine") === "1";
  const participant = readParticipant(search);
  const role = search.get("role");
  return {
    q: search.get("q") ?? "",
    text: searchTermOf(search.get("text")),
    status: [...new Set(search.getAll("status").filter(isAdrStatus))],
    tag: [...new Set(search.getAll("tag").filter((name) => name !== ""))],
    mine,
    participant: mine ? null : participant,
    role: (mine || participant !== null) && isAdrRole(role) ? role : null,
    createdFrom: readDate(search, "createdFrom"),
    createdTo: readDate(search, "createdTo"),
    updatedFrom: readDate(search, "updatedFrom"),
    updatedTo: readDate(search, "updatedTo"),
    expiresBefore: readDate(search, "expiresBefore"),
    hasOpenIssues: readTriState(search, "hasOpenIssues"),
    supersedes: readTriState(search, "supersedes"),
    supersededBy: readTriState(search, "supersededBy"),
  };
}

/** Lays `patch` over the filters in `prev` and returns the new query string state; defaults leave no key behind. */
export function writeAdrListParams(prev: URLSearchParams, patch: Partial<AdrListParams>): URLSearchParams {
  const next = { ...parseAdrListParams(prev), ...patch };
  const search = new URLSearchParams(prev);
  for (const key of OWNED_KEYS) search.delete(key);
  if (next.q !== "") search.set("q", next.q);
  const text = searchTermOf(next.text);
  if (text !== "") search.set("text", text);
  for (const status of next.status) search.append("status", status);
  for (const tag of next.tag) search.append("tag", tag);
  const hasParticipant = next.mine || next.participant !== null;
  if (next.mine) search.set("mine", "1");
  else if (next.participant !== null) search.set("participant", String(next.participant));
  if (hasParticipant && next.role) search.set("role", next.role);
  for (const key of DATE_KEYS) if (isValidIsoDate(next[key])) search.set(key, next[key]);
  for (const key of TRI_STATE_KEYS) if (next[key] !== null) search.set(key, String(next[key]));
  return search;
}

/** An inverted range (`from` after `to`) is the server's `400`; the page holds the request back and says so instead. */
export function isInvertedRange(from: string, to: string): boolean {
  return from !== "" && to !== "" && from > to;
}

/** How many of the Filters panel's filters are active (the badge on its button). The full-text box sits outside the panel and is not counted. */
export function activeAdrFilterCount(params: AdrListParams): number {
  const flags = [
    params.q.trim() !== "",
    params.status.length > 0,
    params.tag.length > 0,
    params.mine || params.participant !== null,
    params.createdFrom !== "" || params.createdTo !== "",
    params.updatedFrom !== "" || params.updatedTo !== "",
    params.expiresBefore !== "",
    ...TRI_STATE_KEYS.map((key) => params[key] !== null),
  ];
  return flags.filter(Boolean).length;
}

/** Whether anything narrows the list at all - the panel's filters or the full-text term. */
export function hasAdrFilters(params: AdrListParams): boolean {
  return params.text !== "" || activeAdrFilterCount(params) > 0;
}

/** The ADR list narrowed to `patch` as a link (the decision log's "+N more" and count links): only the filters the patch sets leave a key. */
export function adrListPath(patch: Partial<AdrListParams>): string {
  const search = writeAdrListParams(new URLSearchParams(), patch).toString();
  return search === "" ? adrsPath : `${adrsPath}?${search}`;
}
