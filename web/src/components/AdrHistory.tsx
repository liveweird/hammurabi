import { useState } from "react";
import { useTranslation } from "react-i18next";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listAdrEvents } from "../api/adrs";
import { adrEventActor, adrEventLines, describeAdrEvent } from "../utils/adrEvents";
import EventTimeline from "./EventTimeline";

/** Ten a page: the history sits in a narrow side panel, newest first. */
const PAGE_SIZE = 10;

/**
 * An ADR's change history: the server's structured events rendered in the viewer's language, newest first (server-ordered, never
 * re-sorted here), ten to a page. Under `["adrs", "events", id, page]`, so any write that refreshes the `["adrs"]` prefix refreshes
 * the trail too. An event's actor is the recorded name (with a note for a deleted account), or "the system" for the expiry sweep.
 */
export default function AdrHistory({ adrId }: { adrId: number }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const { data, isLoading, isError, error, isPlaceholderData, refetch } = useQuery({
    queryKey: ["adrs", "events", adrId, page],
    queryFn: () => listAdrEvents(adrId, { page, pageSize: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });

  // The last total a page told us: a page that fails to load has none, and the pager must stay so the reader can go back.
  const [knownTotal, setKnownTotal] = useState(0);
  if (data && data.total !== knownTotal) setKnownTotal(data.total);

  return (
    <EventTimeline
      items={data?.items.map((event) => ({
        key: event.id,
        title: describeAdrEvent(event, t),
        lines: adrEventLines(event, t),
        who: adrEventActor(event, t),
        when: event.createdAt,
      }))}
      isLoading={isLoading}
      isError={isError}
      error={error}
      isPlaceholder={isPlaceholderData}
      emptyMessage={t("adrs.history.empty")}
      page={page}
      pageSize={PAGE_SIZE}
      total={data?.total ?? knownTotal}
      onPageChange={setPage}
      onRetry={() => void refetch()}
    />
  );
}
