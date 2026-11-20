import { useState } from "react";
import { Modal, Stack, TagsInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { createTagCategory, updateTagCategory, type TagCategory } from "../api/tagCategories";
import {
  EMPTY_TAG_CATEGORY_FORM,
  MAX_CATEGORY_DESCRIPTION_LENGTH,
  MAX_CATEGORY_NAME_LENGTH,
  normalizeTag,
  tagCategoryFormValidation,
  tagCategorySaveErrorMessage,
  toTagCategoryBody,
  toTagCategoryFormValues,
  type TagCategoryFormValues,
} from "../utils/tagCategoryForm";
import { adrConflict } from "../utils/adrErrors";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import RegistryEditorActions from "./RegistryEditorActions";
import RegistryMetadataFields from "./RegistryMetadataFields";

/** Create (target null) / edit (target set) a tag category - one modal, the registry's name + description block plus the tag list. */
export default function TagCategoryEditorModal({
  target,
  onClose,
  onSaved,
}: {
  target: TagCategory | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const form = useForm<TagCategoryFormValues>({
    initialValues: target ? toTagCategoryFormValues(target) : EMPTY_TAG_CATEGORY_FORM,
    validate: tagCategoryFormValidation(t),
  });

  async function save(values: TagCategoryFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      if (target) {
        // `target` is the category as it was when the admin opened the editor: its revision is the one this draft is based on.
        await updateTagCategory(target.id, target.revision, toTagCategoryBody(values));
        showSuccessToast(t("tags.toast.saved"));
      } else {
        await createTagCategory(toTagCategoryBody(values));
        showSuccessToast(t("tags.toast.created"));
      }
      await onSaved();
    } catch (err) {
      setError(tagCategorySaveErrorMessage(err, t));
      setSubmitting(false);
      // A stale revision: the registry the admin looked at is out of date, so re-read it for the next time the category is opened.
      if (adrConflict(err) === "revision") void refreshQueriesAfterMutation(queryClient, ["tagCategories"]);
    }
  }

  return (
    <Modal
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      opened
      onClose={onClose}
      title={target ? t("tags.editTitle") : t("tags.createTitle")}
      centered
    >
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <RegistryMetadataFields
            nameMaxLength={MAX_CATEGORY_NAME_LENGTH}
            descriptionMaxLength={MAX_CATEGORY_DESCRIPTION_LENGTH}
            descriptionLength={form.values.description.length}
            nameInputProps={form.getInputProps("name")}
            descriptionInputProps={form.getInputProps("description")}
          />
          <TagsInput
            label={t("tags.column.tags")}
            description={t("tags.field.tagsHint")}
            splitChars={[","]}
            // A tag is stored in its normal form, so the pills show it already folded (what you add is what the registry keeps).
            onChange={(values) => form.setFieldValue("tags", values.map(normalizeTag))}
            value={form.values.tags}
            error={form.errors.tags}
          />
          <RegistryEditorActions error={error} submitting={submitting} isEdit={Boolean(target)} onClose={onClose} gap="sm" />
        </Stack>
      </form>
    </Modal>
  );
}
