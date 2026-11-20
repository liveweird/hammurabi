import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, MultiSelect, Stack } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { replaceAdrTags, type AdrResponse } from "../api/adrs";
import { useTagCategories } from "../hooks/useTagCategories";
import { hasRetiredTag, liveTagNames, MAX_ADR_TAGS, tagOptionGroups } from "../utils/adrTags";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import AdrWriteError from "./AdrWriteError";
import ErrorAlert from "./ErrorAlert";

/**
 * The tag editor of the side panel: a searchable MultiSelect grouped by registry category, the whole set replaced at once under the
 * revision the editor was OPENED at (a refetch that advances the page's revision meanwhile makes the save a 409, not a lost update). It starts from the live tags only - a retired one is not in the registry, so keeping it would be refused (400)
 * - and says so. A stale revision is the orange reload notice, which closes the editor.
 */
export default function AdrTagsEditor({ adr, onClose }: { adr: AdrResponse; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const categories = useTagCategories();
  const [value, setValue] = useState<string[]>(() => liveTagNames(adr.tags));
  // The tags above were read at this revision: send it, not whatever a later refetch made of the prop.
  const [openedAtRevision] = useState(adr.revision);
  const save = useMutation({
    mutationFn: () => replaceAdrTags(adr.id, openedAtRevision, value),
    onSuccess: async () => {
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.toast.tagsSaved"));
      onClose();
    },
  });
  const data = tagOptionGroups(categories.data ?? []);

  return (
    <Stack gap="xs">
      <MultiSelect
        label={t("adrs.tags.label")}
        description={hasRetiredTag(adr.tags) ? t("adrs.tags.retiredHint") : t("adrs.tags.hint")}
        placeholder={value.length === 0 ? t("adrs.tags.placeholder") : undefined}
        data={data}
        value={value}
        onChange={setValue}
        maxValues={MAX_ADR_TAGS}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("adrs.tags.clear") }}
        nothingFoundMessage={t("adrs.tags.empty")}
        disabled={categories.isLoading}
      />
      {categories.isError && <ErrorAlert error={categories.error} title={t("adrs.tags.loadFailed")} />}
      {save.isError && <AdrWriteError error={save.error} kind="tags" onReloaded={onClose} />}
      <Group gap="xs">
        <Button size="xs" onClick={() => save.mutate()} loading={save.isPending} disabled={categories.isLoading}>
          {t("adrs.tags.save")}
        </Button>
        <Button size="xs" variant="default" onClick={onClose} disabled={save.isPending}>
          {t("common.action.cancel")}
        </Button>
      </Group>
    </Stack>
  );
}
