import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { replaceAdrParticipants, type AdrResponse } from "../api/adrs";
import { participantsRequest, participantsState, type ParticipantsState } from "../utils/adrParticipants";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrParticipantsFields from "./AdrParticipantsFields";
import AdrWriteError from "./AdrWriteError";

/**
 * The DACI editor of the side panel: the whole set at once (the server replaces it as a diff), under the revision the page holds. The
 * Driver seat cannot be left empty (it can be handed over). A stale revision is the orange reload notice, which closes the editor.
 */
export default function AdrParticipantsEditor({ adr, onClose }: { adr: AdrResponse; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [state, setState] = useState<ParticipantsState>(() => participantsState(adr.participants));
  const [driverMissing, setDriverMissing] = useState(false);
  const save = useMutation({
    mutationFn: () => replaceAdrParticipants(adr.id, adr.revision, participantsRequest(state)),
    onSuccess: async () => {
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.toast.participantsSaved"));
      onClose();
    },
  });

  function submit() {
    setDriverMissing(state.driver === null);
    if (state.driver !== null) save.mutate();
  }

  return (
    <Stack gap="sm">
      <AdrParticipantsFields
        value={state}
        withDriver
        onChange={(next) => {
          setState(next);
          setDriverMissing(false);
        }}
        driverError={driverMissing ? t("adrs.validation.driverRequired") : undefined}
      />
      {save.isError && <AdrWriteError error={save.error} kind="participants" onReloaded={onClose} />}
      <Group justify="flex-end" gap="sm">
        <Button variant="default" size="xs" onClick={onClose} disabled={save.isPending}>
          {t("common.action.cancel")}
        </Button>
        <Button size="xs" onClick={submit} loading={save.isPending}>
          {t("adrs.daci.save")}
        </Button>
      </Group>
    </Stack>
  );
}
