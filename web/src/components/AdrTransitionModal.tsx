import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Modal, Stack, Text, Textarea } from "@mantine/core";
import { charCountDescription } from "../utils/charCount";
import { ENDING_MOVES, hasForbiddenReasonCharacter, MAX_TRANSITION_REASON_LENGTH, type ConfirmedMove } from "../utils/adrStatus";

/** Characters as the server counts them (code points), not UTF-16 units. */
const codePoints = (text: string) => Array.from(text).length;

/**
 * The confirmation of a status move that ends an ADR or sends it back (cancel, withdraw, send back): what the move does, and an optional
 * reason (at most 1,000 characters, no control or hidden direction characters - judged here as the server does, so the button stays
 * off) the history keeps. The modal is mounted only while it is open, so each opening starts with an empty reason; the caller owns the
 * mutation and its `loading`. The endings confirm in red, the send back in the brand colour. A confirmed move has no requirement of
 * its own, so when the server answers `400` it is the reason: `rejected` keeps the dialog open with the reason in place and says so,
 * until the reason is edited.
 */
export default function AdrTransitionModal({
  move,
  onClose,
  onConfirm,
  loading,
  rejected = false,
}: {
  move: ConfirmedMove;
  onClose: () => void;
  onConfirm: (reason: string) => void;
  loading: boolean;
  rejected?: boolean;
}) {
  const { t } = useTranslation();
  const [reason, setReason] = useState("");
  // The reason the server last answered 400 to: the refusal speaks for that text only.
  const [sent, setSent] = useState<string | null>(null);
  const length = codePoints(reason);
  const tooLong = length > MAX_TRANSITION_REASON_LENGTH;
  const forbidden = hasForbiddenReasonCharacter(reason);
  const refused = rejected && sent === reason;
  const error = tooLong
    ? t("adrs.validation.textLength", { max: MAX_TRANSITION_REASON_LENGTH })
    : forbidden
      ? t("adrs.validation.reasonCharacters")
      : refused
        ? t("adrs.confirm.reasonRefused")
        : undefined;
  const ending = ENDING_MOVES.has(move);
  return (
    <Modal
      opened
      onClose={() => {
        if (!loading) onClose();
      }}
      title={t(`adrs.confirm.${move}.title`)}
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      centered
    >
      <Stack gap="md">
        <Text>{t(`adrs.confirm.${move}.body`)}</Text>
        <Textarea
          label={t("adrs.confirm.reasonLabel")}
          description={t("adrs.confirm.reasonHint")}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
          autosize
          minRows={3}
          maxRows={8}
          data-autofocus
          error={error}
          inputWrapperOrder={["label", "description", "input", "error"]}
        />
        {charCountDescription(length, MAX_TRANSITION_REASON_LENGTH)}
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={onClose} disabled={loading}>
            {t("common.action.cancel")}
          </Button>
          <Button
            color={ending ? "red" : undefined}
            onClick={() => {
              setSent(reason);
              onConfirm(reason.trim());
            }}
            loading={loading}
            disabled={tooLong || forbidden}
          >
            {t(`adrs.transition.${move}`)}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
