import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Menu, Stack, Table, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { IconPencil, IconPlus, IconTags, IconTrash } from "@tabler/icons-react";
import { deleteTagCategory, type TagCategory } from "../api/tagCategories";
import AdrTagPills from "../components/AdrTagPills";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import PageHeader from "../components/PageHeader";
import RowActionsMenu from "../components/RowActionsMenu";
import TableLoadingRow from "../components/TableLoadingRow";
import TagCategoryEditorModal from "../components/TagCategoryEditorModal";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useTagCategories } from "../hooks/useTagCategories";
import { adrConflict } from "../utils/adrErrors";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";

/**
 * The tag registry (`/tags`, ADMIN-only: the nav entry and the route are - UX only, the server's `requireAdmin` is the rule, and reading the
 * registry stays open to every signed-in user, which the ADR editor's picker relies on): every category with its tags as the same neutral pills an
 * ADR carries, a modal per category (name, description, tags) to create and edit, and a confirmed soft delete. The registry is a
 * bounded, admin-curated set, so it is one table without paging.
 */
export default function Tags() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const categories = useTagCategories();
  const [editorTarget, setEditorTarget] = useState<TagCategory | "new" | null>(null);

  const remove = useDeleteConfirm<TagCategory>({
    // The revision the admin SAW when they chose Delete: the row object is captured at that click, so a refetch behind the open confirm cannot refresh it.
    mutationFn: (category) => deleteTagCategory(category.id, category.revision),
    // The ADRs that carried the category's tags now show them as retired, so the ADR queries are stale too.
    onSuccess: () => refreshQueriesAfterMutation(queryClient, ["tagCategories"], ["adrs"]),
    // A stale revision means the table is out of date: re-read it, so the next look at the category shows what changed.
    onError: (error) => {
      if (adrConflict(error) === "revision") void refreshQueriesAfterMutation(queryClient, ["tagCategories"]);
    },
    successMessage: t("tags.toast.deleted"),
  });

  const items = categories.data ?? [];

  return (
    <Stack gap="md">
      <PageHeader
        title={t("tags.title")}
        description={t("tags.intro")}
        actions={
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")}>
            {t("tags.newCategory")}
          </Button>
        }
      />

      {categories.isError && <ErrorAlert error={categories.error} title={t("tags.loadFailed")} />}
      <Table aria-label={t("tags.title")}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("tags.column.name")}</Table.Th>
            <Table.Th>{t("tags.column.description")}</Table.Th>
            <Table.Th>{t("tags.column.tags")}</Table.Th>
            <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {categories.isLoading ? (
            <TableLoadingRow colSpan={4} />
          ) : items.length > 0 ? (
            items.map((category) => (
              <Table.Tr key={category.id}>
                <Table.Td>
                  <Text size="sm" fw={500}>
                    {category.name}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c={category.description ? undefined : "dimmed"}>
                    {category.description ?? "—"}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <AdrTagPills tags={category.tags.map((name) => ({ name, retired: false }))} />
                </Table.Td>
                <Table.Td style={{ width: 1 }} ta="right">
                  <RowActionsMenu label={t("common.table.operationsAria", { name: category.name })}>
                    <Menu.Item
                      leftSection={<IconPencil size={14} />}
                      onClick={() => setEditorTarget(category)}
                      aria-label={t("common.action.editAria", { name: category.name })}
                    >
                      {t("common.action.edit")}
                    </Menu.Item>
                    <Menu.Divider />
                    <Menu.Item
                      color="red"
                      leftSection={<IconTrash size={14} />}
                      onClick={() => remove.requestDelete(category)}
                      aria-label={t("common.action.deleteAria", { name: category.name })}
                    >
                      {t("common.action.delete")}
                    </Menu.Item>
                  </RowActionsMenu>
                </Table.Td>
              </Table.Tr>
            ))
          ) : !categories.isError ? (
            <Table.Tr>
              <Table.Td colSpan={4}>
                <EmptyState icon={IconTags} label={t("tags.empty")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>

      {editorTarget !== null && (
        <TagCategoryEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await refreshQueriesAfterMutation(queryClient, ["tagCategories"], ["adrs"]);
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("tags.deleteTitle")}
        errorTitle={t("tags.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, { forbidden: "tags.saveForbidden", notFound: "tags.saveGone", conflict: "tags.deleteStale", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })
        }
        body={(category) => t("tags.deleteBody", { name: category.name })}
      />
    </Stack>
  );
}
