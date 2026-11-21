import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Modal, Stack, Textarea } from "@mantine/core";
import { useDraftGuard } from "../hooks/useDraftGuard";
import { charCountDescription } from "../utils/charCount";
import { DISCUSSION_RESOLUTION_MAX } from "../utils/adrDiscussion";

/**
 * Resolving an issue or escalation asks for an optional note (why it is settled, at most 2,000 characters). The modal is mounted only
 * while it is open, so each opening starts with an empty note; the caller owns the mutation and its `loading`.
 */
export default function DiscussionResolveModal({
  error,
  onClose,
  onResolve,
  loading,
}: {
  /** The failure of the last attempt, shown above the buttons so the note the reader typed is never lost with the dialog. */
  error?: ReactNode;
  onClose: () => void;
  onResolve: (note: string) => void;
  loading: boolean;
}) {
  const { t } = useTranslation();
  const [note, setNote] = useState("");
  const { requestDiscard } = useDraftGuard(note !== "");
  const tooLong = note.length > DISCUSSION_RESOLUTION_MAX;
  return (
    <Modal
      opened
      onClose={() => {
        if (!loading) requestDiscard(onClose);
      }}
      title={t("adrs.discussion.resolveTitle")}
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      centered
    >
      <Stack gap="md">
        <Textarea
          label={t("adrs.discussion.resolutionLabel")}
          description={t("adrs.discussion.resolutionHint")}
          value={note}
          onChange={(event) => setNote(event.currentTarget.value)}
          autosize
          minRows={3}
          maxRows={10}
          data-autofocus
          error={tooLong ? t("adrs.validation.textLength", { max: DISCUSSION_RESOLUTION_MAX }) : undefined}
          inputWrapperOrder={["label", "description", "input", "error"]}
        />
        {charCountDescription(note.length, DISCUSSION_RESOLUTION_MAX)}
        {error}
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={() => requestDiscard(onClose)} disabled={loading}>
            {t("common.action.cancel")}
          </Button>
          <Button onClick={() => onResolve(note.trim())} loading={loading} disabled={tooLong}>
            {t("adrs.discussion.action.resolve")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
