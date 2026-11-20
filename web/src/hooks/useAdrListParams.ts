import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import { parseAdrListParams, writeAdrListParams, type AdrListParams } from "../utils/adrListParams";

/**
 * The ADR list's filters, kept in the URL (`utils/adrListParams.ts` is the pure half): a link to a filtered list reproduces it, back/forward
 * and reload keep it. A filter is not a history entry, so writes `replace`; the page and the sort are not URL state (`usePagedSort`).
 */
export function useAdrListParams() {
  const [search, setSearch] = useSearchParams();
  const params = useMemo(() => parseAdrListParams(search), [search]);
  const update = useCallback(
    (patch: Partial<AdrListParams>) => setSearch((prev) => writeAdrListParams(prev, patch), { replace: true }),
    [setSearch],
  );
  return { params, update };
}
