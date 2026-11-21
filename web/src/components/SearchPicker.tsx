import { useState, type ReactNode } from "react";
import { Select } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { FILTER_DEBOUNCE_MS } from "../hooks/usePagedSort";
import ErrorAlert from "./ErrorAlert";

/** The picked entry, held by the caller (the form) so its label survives later searches. */
export type PickedOption = { value: string; label: string };

/**
 * A searchable, clearable single Select over a server-side name search (debounced, `FILTER_DEBOUNCE_MS`):
 * the body behind `UserDirectorySelect` (and the future team and tag pickers). The wrappers own the query and
 * the texts; this owns the search-box mechanics.
 */
export default function SearchPicker({
  label,
  description,
  placeholder,
  clearLabel,
  noMatchLabel,
  queryKey,
  search,
  disabled,
  error,
  picked,
  onPick,
  minTermLength = 0,
  minTermHint,
  exclude,
  maxLength,
  lazy = false,
}: {
  label: string;
  description?: string;
  placeholder: string;
  clearLabel: string;
  /** "No matching …" — states a COMPLETED search for the CURRENT term. */
  noMatchLabel: string;
  /** The query-key prefix; the debounced term is appended. */
  queryKey: readonly unknown[];
  /** Runs the server-side search (page size and sort belong to the caller); `term` is "" for the unfiltered head. */
  search: (term: string) => Promise<PickedOption[]>;
  /** The control is read-only for this session (a plan editor the caller may only look at). */
  disabled?: boolean;
  /** A field error (a server conflict the form maps onto this control). */
  error?: ReactNode;
  picked: PickedOption | null;
  onPick: (picked: PickedOption | null) => void;
  /**
   * A server that REQUIRES a search term (the user directory): the search runs only once the trimmed term has this many characters
   * (code points) and `minTermHint` shows below that, in place of the options and of "No matching …". Default 0: the unfiltered head is searched too.
   */
  minTermLength?: number;
  minTermHint?: string;
  /** Option values left out of the rendered list (people already on a roster). Applied at render, never to the cached search, so it cannot go stale. */
  exclude?: ReadonlySet<string>;
  /** The longest term the box accepts (the server's own limit on the search term). */
  maxLength?: number;
  /**
   * Hold the search back until the dropdown is first opened or the user types (a picker that sits in a panel on every page view must not
   * fetch a list nobody asked to see). Default off: the unfiltered head loads with the control.
   */
  lazy?: boolean;
}) {
  const [engaged, setEngaged] = useState(!lazy);
  const [searchValue, setSearchValue] = useState(picked?.label ?? "");
  // The caller may change the pick without a choice here (clearing a row's own owner hands it back the dialog's): the box follows the
  // pick's label, the derived-state pattern (set during render, no effect), else it would show the old label for a value it no longer holds.
  const [seenLabel, setSeenLabel] = useState(picked?.label ?? "");
  if ((picked?.label ?? "") !== seenLabel) {
    setSeenLabel(picked?.label ?? "");
    setSearchValue(picked?.label ?? "");
  }
  // Choosing an option makes Mantine put its label into the search box — that is not a search term.
  const term = picked !== null && searchValue === picked.label ? "" : searchValue.trim();
  const [debounced] = useDebouncedValue(term, FILTER_DEBOUNCE_MS);
  const settling = term !== debounced;
  const belowMin = codePoints(term) < minTermLength;

  const results = useQuery({
    queryKey: [...queryKey, debounced],
    queryFn: () => search(debounced),
    placeholderData: keepPreviousData,
    enabled: engaged && codePoints(debounced) >= minTermLength,
  });
  const loading = results.isLoading || results.isPlaceholderData;
  // The picked entry leads the options under its own (stable) label: a new term replaces the rows
  // and Mantine drops a value that is no longer in `data`.
  // Below the minimum the previous rows (kept while a new key loads) are not an answer to the shorter term.
  const options = (belowMin ? [] : (results.data ?? [])).filter((o) => o.value !== picked?.value && !exclude?.has(o.value));
  if (picked !== null) options.unshift(picked);

  // "No matching …" states a COMPLETED search for the CURRENT term; below the minimum the hint takes its place.
  const searchCompleted = !(loading || results.isError || settling);
  const nothingFound = belowMin ? minTermHint : searchCompleted ? noMatchLabel : undefined;

  return (
    <>
      <Select
        label={label}
        // The hint is a standing description (aria-describedby), not only the empty dropdown's message: a screen reader hears the minimum on focus.
        description={[description, minTermHint].filter(Boolean).join(" ") || undefined}
        maxLength={maxLength}
        placeholder={placeholder}
        data={options}
        searchable
        clearable
        clearButtonProps={{ "aria-label": clearLabel }}
        searchValue={searchValue}
        onSearchChange={(value) => {
          setSearchValue(value);
          if (value !== "") setEngaged(true);
        }}
        onDropdownOpen={() => setEngaged(true)}
        value={picked?.value ?? null}
        onChange={(value, option) => onPick(value !== null && option ? { value: option.value, label: option.label } : null)}
        nothingFoundMessage={nothingFound}
        disabled={disabled}
        error={error}
        aria-busy={loading}
      />
      {results.isError && !belowMin && <ErrorAlert error={results.error} />}
    </>
  );
}

/** The server counts code points, not UTF-16 units — an emoji or an astral letter is one character. */
function codePoints(text: string): number {
  return [...text].length;
}
