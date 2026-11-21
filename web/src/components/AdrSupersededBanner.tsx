import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Button, Group, Stack } from "@mantine/core";
import { IconArrowForwardUp, IconCheck, IconLinkOff } from "@tabler/icons-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { acceptAdrSuccessor, detachAdrSuccessor, type AdrResponse } from "../api/adrs";
import { adrPath } from "../utils/adrLinks";
import { isPreActive } from "../utils/adrStatus";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrStatusBadge from "./AdrStatusBadge";
import AdrWriteError from "./AdrWriteError";
import ConfirmActionModal from "./ConfirmActionModal";

/**
 * The banner above a replaced ADR: once the successor is active this ADR reads "Superseded by ADR-0042" (orange: it is no longer in force);
 * while the successor is only linked it is a PROPOSAL until this ADR's Approver (or an administrator) accepts it - a grape "waiting" note,
 * with an **Accept** action for whoever the server says may (`canAcceptSuccessor`) - and then a cyan "on its way" note saying what
 * activating it will do. Only while THIS ADR can still be replaced (`ACTIVE` or pre-active: on a cancelled, withdrawn or expired ADR a
 * pending link means nothing, so nothing is shown). Nothing when there is no successor. The server never serves a successor cancelled or withdrawn BEFORE it activated, but a SUPERSEDED
 * predecessor keeps pointing at the successor that replaced it even if that one was withdrawn since: the banner then still names it,
 * with its status badge reading "Withdrawn" (the old decision stays superseded; nothing is resurrected).
 * Whoever the server says may detach the pending successor (`canDetachSuccessor`: this ADR's Driver, Approver or an ADMIN) gets the
 * "Detach" action - linking is the successor's, so this is how the predecessor's owners take back a link parked on it.
 */
export default function AdrSupersededBanner({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const accept = useMutation({
    mutationFn: (successorId: number) => acceptAdrSuccessor(adr.id, successorId),
    onSuccess: async () => {
      setAccepting(false);
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.toast.accepted"));
    },
    onError: () => setAccepting(false),
  });
  const detach = useMutation({
    mutationFn: () => detachAdrSuccessor(adr.id),
    onSuccess: async () => {
      setConfirming(false);
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.toast.detached"));
    },
    onError: () => setConfirming(false),
  });
  const by = adr.supersededBy;
  if (!by) return null;
  const done = adr.status === "SUPERSEDED";
  if (!done && adr.status !== "ACTIVE" && !isPreActive(adr.status)) return null;
  const proposed = !done && !adr.supersededByAccepted;
  return (
    <Stack gap="xs">
      <Alert
        role="status"
        color={done ? "orange" : proposed ? "grape" : "cyan"}
        variant="light"
        icon={<IconArrowForwardUp size={18} />}
        title={done ? t("adrs.banner.superseded.title") : proposed ? t("adrs.banner.proposed.title") : t("adrs.banner.pending.title")}
      >
        <Group gap={6} wrap="wrap">
          {done && <span>{t("adrs.banner.superseded.body")}</span>}
          <Anchor component={RouterLink} to={adrPath(by.id)} fw={500}>
            {by.key} {by.name}
          </Anchor>
          <AdrStatusBadge status={by.status} />
          {!done && <span>{proposed ? t("adrs.banner.proposed.body") : t("adrs.banner.pending.body")}</span>}
          {adr.canAcceptSuccessor && (
            <Button size="compact-xs" variant="filled" leftSection={<IconCheck size={12} />} onClick={() => setAccepting(true)}>
              {t("adrs.banner.proposed.accept")}
            </Button>
          )}
          {adr.canDetachSuccessor && (
            <Button size="compact-xs" variant="default" leftSection={<IconLinkOff size={12} />} onClick={() => setConfirming(true)}>
              {t("adrs.banner.pending.detach")}
            </Button>
          )}
        </Group>
      </Alert>
      {accept.isError && <AdrWriteError error={accept.error} kind="accept" onReloaded={accept.reset} />}
      {detach.isError && <AdrWriteError error={detach.error} kind="detach" onReloaded={detach.reset} />}
      <ConfirmActionModal
        opened={accepting}
        onClose={() => setAccepting(false)}
        title={t("adrs.banner.proposed.acceptTitle", { key: by.key })}
        message={t("adrs.banner.proposed.acceptMessage", { key: by.key })}
        cancelLabel={t("adrs.banner.proposed.cancel")}
        confirmLabel={t("adrs.banner.proposed.acceptConfirm")}
        onConfirm={() => accept.mutate(by.id)}
        loading={accept.isPending}
        confirmColor="hammurabi"
      />
      <ConfirmActionModal
        opened={confirming}
        onClose={() => setConfirming(false)}
        title={t("adrs.banner.pending.detachTitle", { key: by.key })}
        message={t("adrs.banner.pending.detachMessage", { key: by.key })}
        cancelLabel={t("adrs.banner.pending.cancel")}
        confirmLabel={t("adrs.banner.pending.detachConfirm")}
        onConfirm={() => detach.mutate()}
        loading={detach.isPending}
      />
    </Stack>
  );
}
