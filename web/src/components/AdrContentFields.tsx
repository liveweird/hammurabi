import { useTranslation } from "react-i18next";
import { Stack, TextInput } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import { ADR_TEXT_FIELDS, MAX_ADR_NAME_LENGTH, MAX_ADR_TEXT_LENGTH, type AdrFormValues, type AdrTextField } from "../utils/adrForm";
import MarkdownField from "./MarkdownField";

/**
 * The ADR's content fields — the name and the five Markdown fields — bound to a Mantine form (the create page and the editor share
 * them). `fields` narrows the Markdown fields shown (the create page starts with the problem alone); the problem is always required.
 */
export default function AdrContentFields({
  form,
  fields = ADR_TEXT_FIELDS,
  nameRequired,
}: {
  form: UseFormReturnType<AdrFormValues>;
  fields?: readonly AdrTextField[];
  nameRequired: boolean;
}) {
  const { t } = useTranslation();
  return (
    <Stack>
      <TextInput
        label={t("adrs.field.name")}
        description={nameRequired ? undefined : t("adrs.nameDefaultHint")}
        required={nameRequired}
        maxLength={MAX_ADR_NAME_LENGTH}
        {...form.getInputProps("name")}
      />
      {fields.map((field) => (
        <MarkdownField
          key={field}
          label={t(`adrs.field.${field}`)}
          description={t(`adrs.fieldHint.${field}`)}
          required={field === "problem"}
          maxLength={MAX_ADR_TEXT_LENGTH}
          minRows={field === "problem" ? 5 : 4}
          value={form.values[field]}
          onChange={(value) => form.setFieldValue(field, value)}
          error={form.errors[field]}
        />
      ))}
    </Stack>
  );
}
