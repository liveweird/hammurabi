import type { TFunction } from "i18next";
import { saveErrorMessage } from "./saveError";

/** The fixed words of a failed lens write (409 = a name the caller already uses, or the limit of 50 lenses; 403 = someone else's shared lens). */
export function lensErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "lenses.error.forbidden",
    notFound: "lenses.error.gone",
    conflict: "lenses.error.conflict",
    invalid: "lenses.error.invalid",
    failedStatus: "lenses.error.failedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
