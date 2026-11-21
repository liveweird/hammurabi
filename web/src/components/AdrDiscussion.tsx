import { useContext, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack, Switch, Text, Title } from "@mantine/core";
import { IconMessages } from "@tabler/icons-react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listDiscussion } from "../api/adrDiscussion";
import type { AdrResponse } from "../api/adrs";
import { DraftGroupContext, DraftGuardContext } from "../hooks/useDraftGuard";
import { DISCUSSION_PAGE_SIZE, DISCUSSION_VARIANT_KINDS, lastDiscussionPage, type DiscussionVariant } from "../utils/adrDiscussion";
import { isPreActive } from "../utils/adrStatus";
import CompactPagination from "./CompactPagination";
import DiscussionComposer from "./DiscussionComposer";
import DiscussionEntryCard from "./DiscussionEntryCard";
import EmptyState from "./EmptyState";
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
  const [entryDrafts] = useState(() => Symbol());
  const [entryVersion, setEntryVersion] = useState(0);
  const guard = useContext(DraftGuardContext);
  const requestEntriesChange = (change: () => void) => {
    if (guard?.hasDrafts(entryDrafts)) {
      guard.confirm(() => {
        // A filtered page can still include the same entry. Reset its editor even when the row remains.
        setEntryVersion((version) => version + 1);
        change();
      });
    } else change();
  };
  const { data, isLoading, isError, error, isPlaceholderData, refetch } = useQuery({
    queryKey: ["adrs", "discussion", adr.id, variant, page, onlyOpen],
    queryFn: () => listDiscussion(adr.id, { page, pageSize: DISCUSSION_PAGE_SIZE, kind: kinds, state: onlyOpen ? "OPEN" : undefined }),
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
              const checked = event.currentTarget.checked;
              requestEntriesChange(() => {
                setOnlyOpen(checked);
                setPage(1);
              });
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
          <DraftGroupContext.Provider key={entryVersion} value={entryDrafts}>
            {data.items.map((entry) => (
              <DiscussionEntryCard key={entry.id} entry={entry} />
            ))}
          </DraftGroupContext.Provider>
        </Stack>
      ) : (
        <EmptyState compact icon={IconMessages} label={t(onlyOpen ? "adrs.discussion.noneOpen" : `adrs.discussion.${variant}.empty`)} />
      )}

      <CompactPagination page={page} totalPages={lastDiscussionPage(total)} onChange={(next) => requestEntriesChange(() => setPage(next))} />

      {!mayPost && (
        <Text size="sm" c="dimmed">
          {closedNote}
        </Text>
      )}
      <DiscussionComposer
        adrId={adr.id}
        kinds={kinds}
        canPost={mayPost}
        composeLabel={t(`adrs.discussion.${variant}.composeLabel`)}
        onPosted={() => {
          if (!guard?.hasDrafts(entryDrafts)) setPage(lastDiscussionPage(total + 1));
        }}
      />
    </Stack>
  );
}
