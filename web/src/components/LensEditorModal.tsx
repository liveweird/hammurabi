import { Button, Group, Modal, Radio, Stack, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { createLens, updateLens, type Lens, type LensFilters } from "../api/lenses";
import { lensErrorMessage } from "../utils/lensErrors";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import FormErrorAlert from "./FormErrorAlert";

const MAX_LENS_NAME_LENGTH = 100;

type LensFormValues = { name: string; visibility: Lens["visibility"] };

/**
 * Save the current filters as a new lens (`target` null: stores `currentFilters` under the chosen name) or edit one (`target` set: renames it
 * and/or flips its visibility, KEEPING the filters it stores - overwriting them is its own menu action). One PUT/POST of the whole lens.
 */
export default function LensEditorModal({
  target,
  currentFilters,
  onClose,
  onSaved,
}: {
  target: Lens | null;
  currentFilters: LensFilters;
  onClose: () => void;
  /** The saved lens's id, after the lens queries were refreshed. */
  onSaved: (savedId: number) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const form = useForm<LensFormValues>({
    initialValues: { name: target?.name ?? "", visibility: target?.visibility ?? "PRIVATE" },
    validate: {
      name: (value) => {
        if (value.trim() === "") return t("lenses.validation.nameRequired");
        return value.trim().length <= MAX_LENS_NAME_LENGTH ? null : t("lenses.validation.nameTooLong");
      },
    },
  });

  const save = useMutation({
    mutationFn: async (values: LensFormValues): Promise<number> => {
      const body = { name: values.name.trim(), visibility: values.visibility, filters: target ? target.filters : currentFilters };
      if (target) {
        await updateLens(target.id, body);
        return target.id;
      }
      return (await createLens(body)).id;
    },
    onSuccess: async (savedId) => {
      showSuccessToast(t(target ? "lenses.toast.saved" : "lenses.toast.created"));
      await refreshQueriesAfterMutation(queryClient, ["lenses"]);
      onSaved(savedId);
    },
  });

  return (
    <Modal
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      opened
      onClose={onClose}
      title={target ? t("lenses.editTitle") : t("lenses.createTitle")}
      centered
    >
      <form onSubmit={form.onSubmit((values) => save.mutate(values))} noValidate>
        <Stack>
          <TextInput label={t("lenses.field.name")} maxLength={MAX_LENS_NAME_LENGTH} data-autofocus {...form.getInputProps("name")} />
          <Radio.Group label={t("lenses.field.visibility")} {...form.getInputProps("visibility")}>
            <Stack gap="xs" mt="xs">
              <Radio value="PRIVATE" label={t("lenses.visibility.PRIVATE")} description={t("lenses.visibility.privateHint")} />
              <Radio value="PUBLIC" label={t("lenses.visibility.PUBLIC")} description={t("lenses.visibility.publicHint")} />
            </Stack>
          </Radio.Group>
          {save.isError && <FormErrorAlert>{lensErrorMessage(save.error, t)}</FormErrorAlert>}
          <Group justify="flex-end" gap="sm">
            <Button type="button" variant="default" onClick={onClose} disabled={save.isPending}>
              {t("common.action.cancel")}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {t("common.action.save")}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
