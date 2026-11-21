import { useQuery } from "@tanstack/react-query";
import { listLenses } from "../api/lenses";

/** The server's page-size cap; one page is enough for the caller's own lenses (at most 50 each) and the first 100 shared ones. */
const LENS_PAGE_SIZE = 100;

/**
 * The lenses the picker offers, as two cached queries under the `["lenses"]` prefix (every lens write refreshes the prefix): the caller's
 * OWN lenses (`mine=true`, both visibilities) and the PUBLIC lenses of others (`mine=false`), each by name.
 */
export function useLenses() {
  const mine = useQuery({ queryKey: ["lenses", "mine"], queryFn: () => listLenses({ page: 1, pageSize: LENS_PAGE_SIZE, mine: true }) });
  const shared = useQuery({ queryKey: ["lenses", "shared"], queryFn: () => listLenses({ page: 1, pageSize: LENS_PAGE_SIZE, mine: false }) });
  return {
    mine: mine.data?.items ?? [],
    shared: shared.data?.items ?? [],
    error: mine.error ?? shared.error,
    isError: mine.isError || shared.isError,
  };
}
