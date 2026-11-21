import type { TFunction } from "i18next";
import { ApiError } from "../api/http";
import { loadErrorMessage } from "./saveError";

/** The typed 429 of a bulk export past the process cap (`ProblemTypes.kt`): the server is streaming its maximum of exports, retry shortly. */
const EXPORT_BUSY = "urn:hammurabi:problem:export-busy";

/**
 * The words for a failed ADR export. A 404 is the ADR gone; a 429 `export-busy` says to retry in a moment; a 400 on the bulk export is the one the user can act on (more than 500 ADRs
 * match, or a filter the server refuses); everything else (the rate-limit bucket, the search's own 429s, a network failure) speaks the
 * list-load vocabulary.
 */
export function exportErrorMessage(err: unknown, t: TFunction): string {
  if (err instanceof ApiError && err.status === 429 && err.type === EXPORT_BUSY) return t("adrs.export.busy");
  if (err instanceof ApiError && err.status === 404) return t("adrs.error.gone");
  if (err instanceof ApiError && err.status === 400) return t("adrs.export.tooBroad");
  return loadErrorMessage(err, t);
}
