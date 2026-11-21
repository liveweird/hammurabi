import { useState } from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack, Text } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { transitionAdr, type AdrResponse, type AdrStatus } from "../api/adrs";
import { missingForReview } from "../utils/adrAccess";
import { CONFIRMED_MOVES, ENDING_MOVES, isConfirmedMove, isTerminal, moveOf, type AdrMove, type ConfirmedMove } from "../utils/adrStatus";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrStatusBadge from "./AdrStatusBadge";
import AdrTransitionModal from "./AdrTransitionModal";
import AdrWriteError from "./AdrWriteError";

/** The toast of a done move: the send back and a withdrawal say what happened, every other move is "Status changed". */
function movedToast(kind: AdrMove, t: TFunction): string {
  if (kind === "SEND_BACK") return t("adrs.toast.sentBack");
  if (kind === "WITHDRAWN" || kind === "WITHDRAW_ACTIVE") return t("adrs.toast.withdrawn");
  return t("adrs.toast.moved");
}

/**
 * A `400` to a confirmed move (cancel, withdraw, send back): those moves have no requirement of their own (the field checks guard entering
 * review or activation), so the only thing left to refuse is the reason.
 */
const isReasonRefusal = (error: unknown): boolean => error instanceof ApiError && error.status === 400;

type Move = { id: number; revision: number; to: AdrStatus; kind: AdrMove; reason?: string };

/**
 * The Status section: the badge and one button per move THIS caller may request (`allowedTransitions` — the server's verdict, never
 * re-derived here). Forward moves act at once; cancelling, withdrawing (a review or a decision in force) and sending an ADR back to the
 * draft confirm first and take an optional reason, which the history shows. The moves send the revision the user acted on
 * (`X-Expected-Revision`); a stale one, or a move the map no longer allows, is the orange reload notice. Entering review or activation
 * needs the context, decision, consequences and an Approver: the section lists what is still missing (a hint — the server judges).
 */
export default function AdrStatusControl({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState<(Move & { kind: ConfirmedMove }) | null>(null);
  const move = useMutation({
    mutationFn: ({ id, revision, to, reason }: Move) => transitionAdr(id, revision, to, reason),
    onSuccess: async (_adr, { kind }) => {
      setConfirming(null);
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(movedToast(kind, t));
    },
    // A failed ending reports in the section, not inside the modal - except a 400, which for a confirmed move is the reason: the modal
    // stays open with the reason in it.
    onError: (error) => {
      if (!isReasonRefusal(error)) setConfirming(null);
    },
  });
  const reasonRefused = confirming !== null && move.isError && isReasonRefusal(move.error);

  const moves = adr.allowedTransitions.map((to) => ({ to, kind: moveOf(adr.status, to) }));
  // The filled button is the way forward; the send back and the endings are never it.
  const primary = moves.find(({ kind }) => !CONFIRMED_MOVES.has(kind))?.to;
  const needsReviewFields = moves.some(({ to }) => to === "IN_REVIEW" || to === "ACTIVE");
  const missing = needsReviewFields ? missingForReview(adr) : [];
  // Activating over a decision in force needs its Approver's acceptance first (the server refuses with a typed 409 otherwise).
  const waitingForAcceptance = moves.some(({ to }) => to === "ACTIVE") && adr.supersedes?.status === "ACTIVE" && !adr.supersedesAccepted;

  return (
    <Stack gap="xs">
      <Group gap="xs">
        <AdrStatusBadge status={adr.status} />
      </Group>
      {moves.length > 0 ? (
        <Group gap="xs">
          {moves.map(({ to, kind }) => (
            <Button
              key={to}
              size="xs"
              variant={ENDING_MOVES.has(kind) ? "light" : to === primary ? "filled" : "default"}
              color={ENDING_MOVES.has(kind) ? "red" : undefined}
              loading={move.isPending && move.variables?.to === to}
              disabled={move.isPending}
              onClick={() => {
                const target = { id: adr.id, revision: adr.revision, to };
                if (isConfirmedMove(kind)) setConfirming({ ...target, kind });
                else move.mutate({ ...target, kind });
              }}
            >
              {t(`adrs.transition.${kind}`)}
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
      {waitingForAcceptance && (
        <Text size="xs" c="grape" role="status">
          {t("adrs.status.waitingAcceptance", { key: adr.supersedes?.key ?? "" })}
        </Text>
      )}
      {move.isError && !reasonRefused && <AdrWriteError error={move.error} kind="transition" onReloaded={move.reset} />}
      {confirming && (
        <AdrTransitionModal
          move={confirming.kind}
          onClose={() => {
            setConfirming(null);
            if (reasonRefused) move.reset();
          }}
          onConfirm={(reason) => move.mutate({ ...confirming, reason: reason || undefined })}
          loading={move.isPending}
          rejected={reasonRefused}
        />
      )}
    </Stack>
  );
}
