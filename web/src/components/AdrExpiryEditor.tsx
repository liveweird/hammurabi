import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack, TextInput } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateAdr, type AdrResponse } from "../api/adrs";
import { adrFormValues, toUpdateBody } from "../utils/adrForm";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrWriteError from "./AdrWriteError";

/**
 * The expiry-date editor of the Details section. The date is the one field that stays editable once an ADR is `ACTIVE`, so it has its
 * own small form: it sends the document as it is with only the date replaced (an empty field clears it), under the revision the page
 * holds. A date in the past is refused by the server (400), a stale revision is the orange reload notice.
 */
export default function AdrExpiryEditor({ adr, onClose }: { adr: AdrResponse; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [value, setValue] = useState(adr.expiresOn ?? "");
  const save = useMutation({
    mutationFn: () => updateAdr(adr.id, adr.revision, toUpdateBody(adrFormValues(adr), value === "" ? null : value)),
    onSuccess: async () => {
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.toast.expirySaved"));
      onClose();
    },
  });

  return (
    <Stack gap="xs">
      <TextInput
        type="date"
        label={t("adrs.details.expiresOn")}
        description={t("adrs.details.expiryHint")}
        value={value}
        onChange={(e) => setValue(e.currentTarget.value)}
      />
      {save.isError && <AdrWriteError error={save.error} kind="save" onReloaded={onClose} />}
      <Group gap="xs">
        <Button size="xs" onClick={() => save.mutate()} loading={save.isPending}>
          {t("common.action.save")}
        </Button>
        <Button size="xs" variant="default" onClick={onClose} disabled={save.isPending}>
          {t("common.action.cancel")}
        </Button>
      </Group>
    </Stack>
  );
}
