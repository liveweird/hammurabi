import { useState } from "react";
import { ActionIcon, Badge, Group, Menu, Select, Stack } from "@mantine/core";
import { IconDeviceFloppy, IconDots, IconPencil, IconReplace, IconTrash } from "@tabler/icons-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { deleteLens, updateLens, type Lens, type LensFilters } from "../api/lenses";
import { getUserId } from "../api/session";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useLenses } from "../hooks/useLenses";
import { lensErrorMessage } from "../utils/lensErrors";
import { hasFilters, sameFilters } from "../utils/lensFilters";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import ConfirmDeleteModal from "./ConfirmDeleteModal";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import LensEditorModal from "./LensEditorModal";

/**
 * The ADR list's saved filters (Toadie's lens picker, ported): a combo of "My lenses" and "Shared lenses" (a shared one names its creator, as
 * different people may reuse a name) plus an actions menu. Picking a lens REPLACES every filter with its own (`onApply`; the sort and the
 * page size are not part of a lens). The current URL filters (`filters`) are what "Save current filters as lens" stores and "Overwrite"
 * writes. A lens is highlighted while the filters equal it - picked or just found equal - and a picked one that the filters have left shows
 * "Modified". What the user may do with a lens is the server's `canEdit` / `canDelete`.
 */
export default function LensPicker({ filters, onApply }: { filters: LensFilters; onApply: (filters: LensFilters) => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { mine, shared, isError, error } = useLenses();
  const [pickedId, setPickedId] = useState<number | null>(null);
  const [editorTarget, setEditorTarget] = useState<Lens | "new" | null>(null);

  const all = [...mine, ...shared];
  const selected = all.find((lens) => lens.id === pickedId) ?? (hasFilters(filters) ? all.find((lens) => sameFilters(lens.filters, filters)) : undefined) ?? null;
  const modified = selected !== null && !sameFilters(selected.filters, filters);

  const userId = getUserId();
  const option = (lens: Lens) => ({
    value: String(lens.id),
    label: lens.createdById === userId ? lens.name : `${lens.name} — ${lens.createdByName}`,
  });
  const data = [
    { group: t("lenses.group.mine"), items: mine.map(option) },
    { group: t("lenses.group.shared"), items: shared.map(option) },
  ].filter((group) => group.items.length > 0);

  const overwrite = useMutation({
    mutationFn: (lens: Lens) => updateLens(lens.id, { name: lens.name, visibility: lens.visibility, filters }),
    onSuccess: async () => {
      showSuccessToast(t("lenses.toast.saved"));
      await refreshQueriesAfterMutation(queryClient, ["lenses"]);
    },
  });

  const remove = useDeleteConfirm<Lens>({
    mutationFn: (lens) => deleteLens(lens.id),
    onSuccess: async () => {
      setPickedId(null);
      await refreshQueriesAfterMutation(queryClient, ["lenses"]);
    },
    successMessage: t("lenses.toast.deleted"),
  });

  function apply(value: string | null) {
    const lens = all.find((candidate) => String(candidate.id) === value);
    if (!lens) return;
    setPickedId(lens.id);
    overwrite.reset();
    onApply(lens.filters);
  }

  return (
    <Stack gap={4}>
      <Group gap="xs" wrap="nowrap">
        <Select
          size="xs"
          w={240}
          aria-label={t("lenses.picker.label")}
          placeholder={all.length === 0 ? t("lenses.picker.empty") : t("lenses.picker.placeholder")}
          data={data}
          value={selected ? String(selected.id) : null}
          onChange={apply}
          allowDeselect={false}
          searchable
        />
        {selected && (
          <Badge size="sm" variant="light" color="gray">
            {t(`lenses.visibility.${selected.visibility}`)}
          </Badge>
        )}
        {/* Neutral gray: "differs from the saved lens" is state, not caution. */}
        {modified && (
          <Badge size="sm" variant="light" color="gray">
            {t("lenses.modified")}
          </Badge>
        )}
        <Menu position="bottom-start" withinPortal>
          <Menu.Target>
            <ActionIcon variant="default" size="input-xs" aria-label={t("lenses.actionsAria")}>
              <IconDots size={16} />
            </ActionIcon>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Item leftSection={<IconDeviceFloppy size={14} />} disabled={!hasFilters(filters)} onClick={() => setEditorTarget("new")}>
              {t("lenses.action.saveAs")}
            </Menu.Item>
            {selected?.canEdit && (
              <>
                <Menu.Item leftSection={<IconReplace size={14} />} disabled={!modified} onClick={() => overwrite.mutate(selected)}>
                  {t("lenses.action.overwrite")}
                </Menu.Item>
                <Menu.Item leftSection={<IconPencil size={14} />} onClick={() => setEditorTarget(selected)}>
                  {t("lenses.action.edit")}
                </Menu.Item>
              </>
            )}
            {selected?.canDelete && (
              <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => remove.requestDelete(selected)}>
                {t("lenses.action.delete")}
              </Menu.Item>
            )}
          </Menu.Dropdown>
        </Menu>
      </Group>
      {isError && <ErrorAlert error={error} title={t("lenses.picker.loadFailed")} />}
      {overwrite.isError && <FormErrorAlert p="xs">{lensErrorMessage(overwrite.error, t)}</FormErrorAlert>}

      {editorTarget && (
        <LensEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          currentFilters={filters}
          onClose={() => setEditorTarget(null)}
          onSaved={(savedId) => {
            setEditorTarget(null);
            setPickedId(savedId);
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("lenses.deleteTitle")}
        errorTitle={t("lenses.deleteFailed")}
        body={(lens) => t("lenses.deleteBody", { name: lens.name })}
        errorMessage={(err) => lensErrorMessage(err, t)}
      />
    </Stack>
  );
}
