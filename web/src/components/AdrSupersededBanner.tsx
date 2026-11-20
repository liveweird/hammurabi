import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Button, Group, Stack } from "@mantine/core";
import { IconArrowForwardUp, IconLinkOff } from "@tabler/icons-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { detachAdrSuccessor, type AdrResponse } from "../api/adrs";
import { adrPath } from "../utils/adrLinks";
import { isPreActive } from "../utils/adrStatus";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrStatusBadge from "./AdrStatusBadge";
import AdrWriteError from "./AdrWriteError";
import ConfirmActionModal from "./ConfirmActionModal";

/**
 * The banner above a replaced ADR: once the successor is active this ADR reads "Superseded by ADR-0042" (orange: it is no longer in force);
 * while the successor is only linked, a cyan note says one is on its way and what activating it will do - and only while THIS ADR can still
 * be replaced (`ACTIVE` or pre-active: on a cancelled, withdrawn or expired ADR a pending link means nothing, so nothing is shown). Nothing
 * when there is no successor, or when the one linked is itself over (the server never serves a cancelled or withdrawn one here).
 * Whoever the server says may detach the pending successor (`canDetachSuccessor`: this ADR's Driver, Approver or an ADMIN) gets the
 * "Detach" action - linking is the successor's, so this is how the predecessor's owners take back a link parked on it.
 */
export default function AdrSupersededBanner({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
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
  return (
    <Stack gap="xs">
      <Alert
        role="status"
        color={done ? "orange" : "cyan"}
        variant="light"
        icon={<IconArrowForwardUp size={18} />}
        title={done ? t("adrs.banner.superseded.title") : t("adrs.banner.pending.title")}
      >
        <Group gap={6} wrap="wrap">
          {done && <span>{t("adrs.banner.superseded.body")}</span>}
          <Anchor component={RouterLink} to={adrPath(by.id)} fw={500}>
            {by.key} {by.name}
          </Anchor>
          <AdrStatusBadge status={by.status} />
          {!done && <span>{t("adrs.banner.pending.body")}</span>}
          {adr.canDetachSuccessor && (
            <Button size="compact-xs" variant="default" leftSection={<IconLinkOff size={12} />} onClick={() => setConfirming(true)}>
              {t("adrs.banner.pending.detach")}
            </Button>
          )}
        </Group>
      </Alert>
      {detach.isError && <AdrWriteError error={detach.error} kind="detach" onReloaded={detach.reset} />}
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
