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
 * `participantId`, the shortcut the panel's switch writes) - `participantId` itself is only ever another user's id, which a shared lens can carry.
 * Links written before 1.0.0 spell four keys the old way ([LEGACY_KEYS]): they are still READ, never written again.
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
  participantId: number | null;
  /** Only meaningful with `mine` or `participantId`. */
  participantRole: AdrRole | null;
  /** `YYYY-MM-DD` UTC days, "" = none. */
  createdFrom: string;
  createdTo: string;
  updatedFrom: string;
  updatedTo: string;
  expiresBefore: string;
  hasOpenIssues: TriState;
  hasPredecessor: TriState;
  hasSuccessor: TriState;
};

export const DATE_KEYS = ["createdFrom", "createdTo", "updatedFrom", "updatedTo", "expiresBefore"] as const;
export const TRI_STATE_KEYS = ["hasOpenIssues", "hasPredecessor", "hasSuccessor"] as const;

/**
 * The four keys 1.0.0 renamed (current -> old spelling), READ-ONLY: a bookmark or a shared link from 0.9.x still filters, and the first
 * write of the filters drops the old spelling (it is in [OWNED_KEYS]). When both spellings are present the current one wins.
 */
const LEGACY_KEYS: Readonly<Record<string, string | undefined>> = {
  participantId: "participant",
  participantRole: "role",
  hasPredecessor: "supersedes",
  hasSuccessor: "supersededBy",
};

/** The value of `key`, or of its pre-1.0.0 spelling when the current one is absent. */
function readKey(search: URLSearchParams, key: string): string | null {
  const legacy = LEGACY_KEYS[key];
  return search.get(key) ?? (legacy === undefined ? null : search.get(legacy));
}

/** Every key this module owns in the query string; anything else (foreign keys) is left alone. */
const OWNED_KEYS = ["q", "text", "status", "tag", "mine", "participantId", "participantRole", ...DATE_KEYS, ...TRI_STATE_KEYS, ...Object.values(LEGACY_KEYS).filter((key) => key !== undefined)];

/** No filter at all - what "clear all" and a lens without a key write. */
export const NO_ADR_FILTERS: AdrListParams = {
  q: "",
  text: "",
  status: [],
  tag: [],
  mine: false,
  participantId: null,
  participantRole: null,
  createdFrom: "",
  createdTo: "",
  updatedFrom: "",
  updatedTo: "",
  expiresBefore: "",
  hasOpenIssues: null,
  hasPredecessor: null,
  hasSuccessor: null,
};

function readTriState(search: URLSearchParams, key: string): TriState {
  const value = readKey(search, key);
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
  const raw = readKey(search, "participantId") ?? "";
  return /^\d+$/.test(raw) && Number(raw) > 0 ? Number(raw) : null;
}

/** Reads the filters, ignoring anything the list does not know (a stale or hand-edited link falls back to "no filter"). */
export function parseAdrListParams(search: URLSearchParams): AdrListParams {
  const mine = search.get("mine") === "1";
  const participantId = readParticipant(search);
  const role = readKey(search, "participantRole");
  return {
    q: search.get("q") ?? "",
    text: searchTermOf(search.get("text")),
    status: [...new Set(search.getAll("status").filter(isAdrStatus))],
    tag: [...new Set(search.getAll("tag").filter((name) => name !== ""))],
    mine,
    participantId: mine ? null : participantId,
    participantRole: (mine || participantId !== null) && isAdrRole(role) ? role : null,
    createdFrom: readDate(search, "createdFrom"),
    createdTo: readDate(search, "createdTo"),
    updatedFrom: readDate(search, "updatedFrom"),
    updatedTo: readDate(search, "updatedTo"),
    expiresBefore: readDate(search, "expiresBefore"),
    hasOpenIssues: readTriState(search, "hasOpenIssues"),
    hasPredecessor: readTriState(search, "hasPredecessor"),
    hasSuccessor: readTriState(search, "hasSuccessor"),
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
  const hasParticipant = next.mine || next.participantId !== null;
  if (next.mine) search.set("mine", "1");
  else if (next.participantId !== null) search.set("participantId", String(next.participantId));
  if (hasParticipant && next.participantRole) search.set("participantRole", next.participantRole);
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
    params.mine || params.participantId !== null,
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
