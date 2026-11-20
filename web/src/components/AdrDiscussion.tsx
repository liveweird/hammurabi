import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Pagination, Stack, Switch, Text, Title } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listDiscussion } from "../api/adrDiscussion";
import type { AdrResponse } from "../api/adrs";
import { DISCUSSION_PAGE_SIZE, DISCUSSION_VARIANT_KINDS, lastDiscussionPage, type DiscussionVariant } from "../utils/adrDiscussion";
import { isPreActive } from "../utils/adrStatus";
import DiscussionComposer from "./DiscussionComposer";
import DiscussionEntryCard from "./DiscussionEntryCard";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";

/**
 * One discussion section of the ADR page (`adr-model.md` "Discussion"): "discussion" holds comments and questions, "issues" holds issues
 * and escalations. The thread is oldest first, twenty to a page, under `["adrs", "discussion", id, variant, ...]` so any write that
 * refreshes the `["adrs"]` prefix refreshes it too. The composer shows while the server says the ADR takes the section's kinds
 * (`canComment` / `canRaiseIssue`); in every other status the section is read-only and says why. The issues section can narrow to the open ones.
 */
export default function AdrDiscussion({ adr, variant }: { adr: AdrResponse; variant: DiscussionVariant }) {
  const { t } = useTranslation();
  const kinds = DISCUSSION_VARIANT_KINDS[variant];
  const [page, setPage] = useState(1);
  const [onlyOpen, setOnlyOpen] = useState(false);
  const { data, isLoading, isError, error, isPlaceholderData, refetch } = useQuery({
    queryKey: ["adrs", "discussion", adr.id, variant, page, onlyOpen],
    queryFn: () => listDiscussion(adr.id, { page, pageSize: DISCUSSION_PAGE_SIZE, kind: kinds, status: onlyOpen ? "OPEN" : undefined }),
    placeholderData: keepPreviousData,
  });

  // The last total a page told us: a page that fails to load has none, and the pager must stay so the reader can go back.
  const [knownTotal, setKnownTotal] = useState(0);
  if (data && data.total !== knownTotal) setKnownTotal(data.total);
  const total = data?.total ?? knownTotal;
  // Entries went away (deleted elsewhere, or by this reader) and the page we are on no longer exists: step back to the last one.
  if (data && !isPlaceholderData && page > lastDiscussionPage(data.total)) setPage(lastDiscussionPage(data.total));

  const mayPost = variant === "discussion" ? adr.canComment : adr.canRaiseIssue;
  const closedNote =
    variant === "discussion"
      ? t("adrs.discussion.discussion.closedNote")
      : t(isPreActive(adr.status) ? "adrs.discussion.issues.notYetNote" : "adrs.discussion.issues.closedNote");

  return (
    <Stack gap="md" component="section" aria-label={t(`adrs.discussion.${variant}.title`)}>
      <Group justify="space-between">
        <Title order={3} size="h4">
          {t(`adrs.discussion.${variant}.title`)}
        </Title>
        {variant === "issues" && (
          <Switch
            label={t("adrs.discussion.onlyOpen")}
            checked={onlyOpen}
            onChange={(event) => {
              setOnlyOpen(event.currentTarget.checked);
              setPage(1);
            }}
          />
        )}
      </Group>

      {isLoading ? (
        <LoadingBlock py="sm" />
      ) : isError ? (
        <Stack gap="sm" align="flex-start">
          <ErrorAlert error={error} />
          <Button variant="default" size="compact-sm" onClick={() => void refetch()}>
            {t("common.action.retry")}
          </Button>
        </Stack>
      ) : data && data.items.length > 0 ? (
        <Stack gap="sm" aria-busy={isPlaceholderData} style={isPlaceholderData ? { opacity: 0.6 } : undefined}>
          {data.items.map((entry) => (
            <DiscussionEntryCard key={entry.id} entry={entry} />
          ))}
        </Stack>
      ) : (
        <Text c="dimmed" size="sm">
          {t(onlyOpen ? "adrs.discussion.noneOpen" : `adrs.discussion.${variant}.empty`)}
        </Text>
      )}

      {total > DISCUSSION_PAGE_SIZE && (
        <Group justify="center">
          <Pagination
            size="sm"
            value={page}
            onChange={setPage}
            total={lastDiscussionPage(total)}
            getControlProps={(control) => ({ "aria-label": t(`common.table.${control}Page`) })}
          />
        </Group>
      )}

      {mayPost ? (
        <DiscussionComposer
          adrId={adr.id}
          kinds={kinds}
          composeLabel={t(`adrs.discussion.${variant}.composeLabel`)}
          onPosted={() => setPage(lastDiscussionPage(total + 1))}
        />
      ) : (
        <Text size="sm" c="dimmed">
          {closedNote}
        </Text>
      )}
    </Stack>
  );
}
