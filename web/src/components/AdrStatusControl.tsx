import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack, Text } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { transitionAdr, type AdrResponse, type AdrStatus } from "../api/adrs";
import { missingForReview } from "../utils/adrAccess";
import { isTerminal } from "../utils/adrStatus";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrStatusBadge from "./AdrStatusBadge";
import AdrWriteError from "./AdrWriteError";
import ConfirmActionModal from "./ConfirmActionModal";

/** The two endings a user chooses, which ask for a confirmation first. */
type Ending = "CANCELLED" | "WITHDRAWN";
const isEnding = (to: AdrStatus): to is Ending => to === "CANCELLED" || to === "WITHDRAWN";

/**
 * The Status section: the badge and one button per move THIS caller may request (`allowedTransitions` — the server's verdict, never
 * re-derived here). Forward moves act at once; cancelling and withdrawing confirm first. The moves send the revision the page holds
 * (`X-Expected-Revision`); a stale one, or a move the map no longer allows, is the orange reload notice. Entering review or activation
 * needs the context, decision, consequences and an Approver: the section lists what is still missing (a hint — the server judges).
 */
export default function AdrStatusControl({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState<Ending | null>(null);
  const move = useMutation({
    mutationFn: (to: AdrStatus) => transitionAdr(adr.id, adr.revision, to),
    onSuccess: async () => {
      setConfirming(null);
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.toast.moved"));
    },
    // A failed ending reports in the section, not inside the modal.
    onError: () => setConfirming(null),
  });

  const moves = adr.allowedTransitions;
  const primary = moves.find((to) => !isEnding(to));
  const needsReviewFields = moves.some((to) => to === "IN_REVIEW" || to === "ACTIVE");
  const missing = needsReviewFields ? missingForReview(adr) : [];

  return (
    <Stack gap="xs">
      <Group gap="xs">
        <AdrStatusBadge status={adr.status} />
      </Group>
      {moves.length > 0 ? (
        <Group gap="xs">
          {moves.map((to) => (
            <Button
              key={to}
              size="xs"
              variant={isEnding(to) ? "light" : to === primary ? "filled" : "default"}
              color={isEnding(to) ? "red" : undefined}
              loading={move.isPending && move.variables === to}
              disabled={move.isPending}
              onClick={() => (isEnding(to) ? setConfirming(to) : move.mutate(to))}
            >
              {t(`adrs.transition.${to}`)}
            </Button>
          ))}
        </Group>
      ) : (
        <Text size="sm" c="dimmed">
          {isTerminal(adr.status) ? t("adrs.status.closed") : t("adrs.status.noMoves")}
        </Text>
      )}
      {missing.length > 0 && (
        <Text size="xs" c="dimmed">
          {t("adrs.status.missing", { list: missing.map((item) => t(`adrs.requirement.${item}`)).join(", ") })}
        </Text>
      )}
      {move.isError && <AdrWriteError error={move.error} kind="transition" onReloaded={move.reset} />}
      <ConfirmActionModal
        opened={confirming !== null}
        onClose={() => setConfirming(null)}
        title={confirming ? t(`adrs.confirm.${confirming}.title`) : ""}
        message={confirming ? t(`adrs.confirm.${confirming}.body`) : ""}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={confirming ? t(`adrs.transition.${confirming}`) : ""}
        onConfirm={() => confirming && move.mutate(confirming)}
        loading={move.isPending}
      />
    </Stack>
  );
}
