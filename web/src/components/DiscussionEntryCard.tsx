import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Button, Group, Paper, Stack, Text } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { IconPencil, IconTrash } from "@tabler/icons-react";
import { deleteDiscussionEntry, setDiscussionEntryState, type DiscussionEntry, type DiscussionState } from "../api/adrDiscussion";
import { browserTimeZone, formatDate, formatDateTime } from "../utils/formatDate";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrWriteError from "./AdrWriteError";
import ConfirmActionModal from "./ConfirmActionModal";
import DiscussionEntryEditor from "./DiscussionEntryEditor";
import DiscussionKindBadge from "./DiscussionKindBadge";
import DiscussionResolveModal from "./DiscussionResolveModal";
import DiscussionStateBadge from "./DiscussionStateBadge";
import MarkdownView from "./MarkdownView";

/**
 * One entry of a discussion thread: the kind and state badges, the author and date, the Markdown text (or the tombstone placeholder of a
 * deleted entry), the resolution note, and - only what THIS caller's verdicts (`canEdit`, `canDelete`, `canChangeState`, the server's)
 * allow - Edit, Delete and the state button (answer a question; resolve with an optional note, or reopen). A typed conflict (the ADR
 * moved on) is the orange reload notice under the entry; every other failure a red alert in fixed words.
 */
export default function DiscussionEntryCard({ entry }: { entry: DiscussionEntry }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [resolving, setResolving] = useState(false);
  const refresh = () => refreshQueriesAfterMutation(queryClient, ["adrs"]);
  const remove = useMutation({
    mutationFn: () => deleteDiscussionEntry(entry.adrId, entry.id),
    onSuccess: async () => {
      setConfirmingDelete(false);
      await refresh();
      showSuccessToast(t("adrs.discussion.toast.deleted"));
    },
    onError: () => setConfirmingDelete(false),
  });
  const move = useMutation({
    mutationFn: (input: { state: DiscussionState; note?: string }) => setDiscussionEntryState(entry.adrId, entry.id, input.state, input.note),
    onSuccess: async (_entry, input) => {
      setResolving(false);
      await refresh();
      showSuccessToast(t(`adrs.discussion.toast.${input.state}`));
    },
    // A failed resolve keeps the dialog and the note open (the error shows inside it); only a success closes it.
  });

  const open = entry.state === "OPEN";
  const question = entry.kind === "QUESTION";
  const stateLabel = open ? (question ? "markAnswered" : "resolve") : question ? "reopenQuestion" : "reopenIssue";
  // A question is answered at once; an issue or escalation is resolved through the note dialog; either reopens at once.
  const changeState = () => {
    if (!open) move.mutate({ state: "OPEN" });
    else if (question) move.mutate({ state: "ANSWERED" });
    else setResolving(true);
  };
  const settledBy = entry.stateChangedByName != null && entry.stateChangedAt != null ? { name: entry.stateChangedByName, date: formatDate(entry.stateChangedAt, "—", browserTimeZone()) } : null;

  return (
    <Paper withBorder p="md" component="article" aria-label={t("adrs.discussion.entryAria", { kind: t(`adrs.discussion.kind.${entry.kind}`), name: entry.authorName })}>
      <Stack gap="xs">
        <Group justify="space-between" align="flex-start" wrap="nowrap">
          <Group gap="xs" align="center">
            <DiscussionKindBadge kind={entry.kind} />
            {entry.state && <DiscussionStateBadge state={entry.state} />}
            <Text size="sm" fw={500}>
              {entry.authorDeleted ? t("adrs.history.deletedAccount", { name: entry.authorName }) : entry.authorName}
            </Text>
            <Text size="xs" c="dimmed">
              {formatDateTime(entry.createdAt, browserTimeZone())}
              {entry.editedAt != null && !entry.deleted ? ` · ${t("adrs.discussion.edited")}` : ""}
            </Text>
          </Group>
          <Group gap={4} wrap="nowrap">
            {entry.canEdit && !editing && (
              <ActionIcon aria-label={t("adrs.discussion.action.editAria", { name: entry.authorName })} onClick={() => setEditing(true)}>
                <IconPencil size={16} />
              </ActionIcon>
            )}
            {entry.canDelete && (
              <ActionIcon color="red" aria-label={t("adrs.discussion.action.deleteAria", { name: entry.authorName })} onClick={() => setConfirmingDelete(true)}>
                <IconTrash size={16} />
              </ActionIcon>
            )}
          </Group>
        </Group>

        {entry.deleted ? (
          <Text size="sm" c="dimmed" fs="italic">
            {t("adrs.discussion.deleted")}
          </Text>
        ) : editing ? (
          <DiscussionEntryEditor entry={entry} onClose={() => setEditing(false)} />
        ) : (
          <MarkdownView variant="message" idPrefix={`entry-${entry.id}-`}>
            {entry.body}
          </MarkdownView>
        )}

        {entry.resolution && (
          <Paper withBorder p="xs" bg="var(--mantine-color-default-hover)">
            <Text size="xs" fw={600}>
              {t("adrs.discussion.resolutionLabel")}
            </Text>
            <MarkdownView variant="message" idPrefix={`entry-${entry.id}-note-`}>
              {entry.resolution}
            </MarkdownView>
          </Paper>
        )}
        {settledBy && entry.state && !open && (
          <Text size="xs" c="dimmed">
            {t(question ? "adrs.discussion.answeredBy" : "adrs.discussion.resolvedBy", settledBy)}
          </Text>
        )}

        {entry.canChangeState && (
          <Group>
            <Button size="compact-sm" variant="default" loading={move.isPending} onClick={changeState}>
              {t(`adrs.discussion.action.${stateLabel}`)}
            </Button>
          </Group>
        )}
        {move.isError && !resolving && <AdrWriteError error={move.error} kind="discussion" onReloaded={move.reset} />}
        {remove.isError && <AdrWriteError error={remove.error} kind="discussion" onReloaded={remove.reset} />}
      </Stack>
      {resolving && (
        <DiscussionResolveModal
          error={move.isError ? <AdrWriteError error={move.error} kind="discussion" onReloaded={move.reset} /> : null}
          onClose={() => {
            setResolving(false);
            move.reset();
          }}
          onResolve={(note) => move.mutate({ state: "RESOLVED", note: note || undefined })}
          loading={move.isPending}
        />
      )}
      <ConfirmActionModal
        opened={confirmingDelete}
        onClose={() => setConfirmingDelete(false)}
        title={t("adrs.discussion.deleteTitle")}
        message={t("adrs.discussion.deleteBody")}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t("common.action.delete")}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
      />
    </Paper>
  );
}
