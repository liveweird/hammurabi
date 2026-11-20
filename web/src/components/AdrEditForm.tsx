import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Paper, Stack, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { updateAdr, type AdrResponse } from "../api/adrs";
import { adrFormValidation, adrFormValues, toUpdateBody, type AdrFormValues } from "../utils/adrForm";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrContentFields from "./AdrContentFields";
import AdrWriteError from "./AdrWriteError";

/**
 * The inline editor of an ADR's content (name and the five Markdown fields), shown in place of the sections while editing. It saves the
 * whole document under the revision the form was OPENED at (`X-Expected-Revision`: a refetch that advances the page's revision meanwhile makes the save a 409, not a lost update) and keeps the expiry date as it is (the Details section owns
 * that). A stale revision, a freeze or a refused move is the orange notice offering a reload, which closes the editor and drops the draft.
 */
export default function AdrEditForm({ adr, onClose }: { adr: AdrResponse; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const titleId = useId();
  const form = useForm<AdrFormValues>({ initialValues: adrFormValues(adr), validate: adrFormValidation(t, { nameRequired: true }) });
  // The draft started from the document at this revision: send it, not whatever a later refetch made of the prop.
  const [openedAtRevision] = useState(adr.revision);
  const save = useMutation({
    mutationFn: (values: AdrFormValues) => updateAdr(adr.id, openedAtRevision, toUpdateBody(values, adr.expiresOn)),
    onSuccess: async () => {
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.toast.saved"));
      onClose();
    },
  });

  return (
    <Paper withBorder p="md" component="section" aria-labelledby={titleId}>
      <form onSubmit={form.onSubmit((values) => save.mutate(values))} noValidate>
        <Stack>
          <Title order={3} size="h4" id={titleId}>
            {t("adrs.editTitle")}
          </Title>
          <AdrContentFields form={form} nameRequired />
          {save.isError && <AdrWriteError error={save.error} kind="save" onReloaded={onClose} />}
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={onClose} disabled={save.isPending}>
              {t("common.action.cancel")}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {t("common.action.save")}
            </Button>
          </Group>
        </Stack>
      </form>
    </Paper>
  );
}
