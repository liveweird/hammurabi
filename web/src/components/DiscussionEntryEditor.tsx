import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { editDiscussionEntry, type DiscussionEntry } from "../api/adrDiscussion";
import { useDraftGuard } from "../hooks/useDraftGuard";
import { DISCUSSION_BODY_MAX } from "../utils/adrDiscussion";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrWriteError from "./AdrWriteError";
import MarkdownField from "./MarkdownField";

/**
 * The inline editor of one entry's text (the author only; the server judges): the Markdown field prefilled with the saved text, Save and
 * Cancel. A closed window (the ADR moved on) is the orange reload notice, which also drops the draft.
 */
export default function DiscussionEntryEditor({ entry, onClose }: { entry: DiscussionEntry; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [initialBody] = useState(entry.body ?? "");
  const [body, setBody] = useState(initialBody);
  const [revision] = useState(entry.revision);
  const { markSaved, requestDiscard } = useDraftGuard(body !== initialBody);
  const save = useMutation({
    mutationFn: () => editDiscussionEntry(entry.adrId, entry.id, revision, body.trim()),
    onSuccess: async () => {
      markSaved();
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.discussion.toast.edited"));
      onClose();
    },
  });
  const tooLong = body.length > DISCUSSION_BODY_MAX;

  return (
    <Stack gap="xs">
      <MarkdownField
        label={t("adrs.discussion.editLabel")}
        value={body}
        onChange={setBody}
        maxLength={DISCUSSION_BODY_MAX}
        minRows={3}
        error={tooLong ? t("adrs.validation.textLength", { max: DISCUSSION_BODY_MAX }) : undefined}
      />
      {save.isError && <AdrWriteError error={save.error} kind="discussion" onReloaded={onClose} />}
      <Group justify="flex-end" gap="sm">
        <Button variant="default" onClick={() => requestDiscard(onClose)} disabled={save.isPending}>
          {t("common.action.cancel")}
        </Button>
        <Button onClick={() => save.mutate()} loading={save.isPending} disabled={body.trim() === "" || tooLong}>
          {t("common.action.save")}
        </Button>
      </Group>
    </Stack>
  );
}
