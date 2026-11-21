import { useCallback, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useDebouncedCallback } from "@mantine/hooks";
import { isUnsearchable, parseAdrListParams, searchTermOf, writeAdrListParams, type AdrListParams } from "../utils/adrListParams";
import { FILTER_DEBOUNCE_MS } from "./usePagedSort";

/**
 * The ADR list's filters, kept in the URL (`utils/adrListParams.ts` is the pure half): a link to a filtered list reproduces it, back/forward
 * and reload keep it. A filter is not a history entry, so writes `replace`; the page and the sort are not URL state (`usePagedSort`).
 *
 * The full-text box is the one filter typed faster than it should travel: `search.value` follows every keystroke, and the trimmed term is
 * committed to the URL (and so to the request) 300 ms after the last one. Only a term the server accepts travels (`searchTermOf`: two code
 * points at least, one of them a letter or digit); anything else commits NO term, so the rows never belong to a term the box no longer shows
 * (`search.tooShort` is what the box hints). A URL change from elsewhere (a lens, "clear all", back) wins over a pending commit and over the box.
 */
export function useAdrListParams() {
  const [search, setSearch] = useSearchParams();
  const params = useMemo(() => parseAdrListParams(search), [search]);

  // The box's own text; it follows the URL when the URL changes under it (adjusting state during render, not an effect).
  const [typed, setTyped] = useState(params.text);
  const [seenText, setSeenText] = useState(params.text);
  if (seenText !== params.text) {
    setSeenText(params.text);
    if (searchTermOf(typed) !== params.text) setTyped(params.text);
  }

  const commitText = useDebouncedCallback(
    (value: string) => setSearch((prev) => writeAdrListParams(prev, { text: searchTermOf(value) }), { replace: true }),
    FILTER_DEBOUNCE_MS,
  );

  const update = useCallback(
    (patch: Partial<AdrListParams>) => {
      if (patch.text !== undefined) {
        commitText.cancel();
        setTyped(patch.text);
      }
      setSearch((prev) => writeAdrListParams(prev, patch), { replace: true });
    },
    [setSearch, commitText],
  );

  const onTextChange = useCallback(
    (value: string) => {
      setTyped(value);
      commitText(value);
    },
    [commitText],
  );

  return { params, update, search: { value: typed, onChange: onTextChange, tooShort: isUnsearchable(typed) } };
}
