import { useRef, useState, type SubmitEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack, TextInput } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateAdr, type AdrResponse } from "../api/adrs";
import { useDraftGuard } from "../hooks/useDraftGuard";
import { adrFormValues, toUpdateBody } from "../utils/adrForm";
import { adrConflict } from "../utils/adrErrors";
import { addDays, isValidIsoDate } from "../utils/isoDate";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrWriteError from "./AdrWriteError";
import FormErrorAlert from "./FormErrorAlert";

/** Updates only the expiry date on the document snapshot the user opened. */
export default function AdrExpiryEditor({ original, extending, canSave, onClose }: {
  original: AdrResponse;
  extending: boolean;
  canSave: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const initialValue = extending ? "" : (original.expiresOn ?? "");
  const [value, setValue] = useState(initialValue);
  const [attempted, setAttempted] = useState(false);
  const dateInput = useRef<HTMLInputElement>(null);
  const oldDay = original.expiresOn ?? "";
  const dayAfter = extending && oldDay ? addDays(oldDay, 1) : "";
  const invalidDate = extending && (!isValidIsoDate(value) || value <= oldDay);
  const { markSaved, requestDiscard } = useDraftGuard(value !== initialValue);
  const save = useMutation({
    mutationFn: (date: string) => updateAdr(original.id, original.revision, toUpdateBody(adrFormValues(original), date || null)),
    onSuccess: async (_result, date) => {
      markSaved();
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(extending ? t("adrs.validity.extended", { date }) : t("adrs.toast.expirySaved"));
      onClose();
    },
    onError: async (error) => {
      if (extending && adrConflict(error) === "frozen") await refreshQueriesAfterMutation(queryClient, ["adrs"]);
    },
  });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSave || save.isPending) return;
    setAttempted(true);
    if (invalidDate) {
      dateInput.current?.focus();
      return;
    }
    save.mutate(value);
  }

  return (
    <form noValidate onSubmit={submit}>
      <Stack gap="xs">
        <TextInput
          ref={dateInput}
          autoFocus
          name="expiresOn"
          type="date"
          label={t(extending ? "adrs.validity.newLastDay" : "adrs.details.expiresOn")}
          description={extending ? undefined : t("adrs.details.expiryHint")}
          min={isValidIsoDate(dayAfter) ? dayAfter : undefined}
          required={extending}
          disabled={save.isPending || !canSave}
          error={attempted && invalidDate ? <span role="alert">{t("adrs.validity.laterDate", { date: oldDay })}</span> : undefined}
          value={value}
          onChange={(e) => setValue(e.currentTarget.value)}
        />
        {save.isError && <AdrWriteError error={save.error} kind="save" onReloaded={onClose} />}
        {!canSave && !save.isError && <FormErrorAlert>{t("adrs.validity.unavailable")}</FormErrorAlert>}
        <Group gap="xs">
          <Button type="submit" size="xs" loading={save.isPending} disabled={!canSave}>
            {t(extending ? "adrs.validity.extend" : "common.action.save")}
          </Button>
          <Button type="button" size="xs" variant="default" onClick={() => requestDiscard(onClose)} disabled={save.isPending}>
            {t("common.action.cancel")}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
