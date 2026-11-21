import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { Button, Grid, Stack } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { IconPencil, IconTrash } from "@tabler/icons-react";
import { deleteAdr, exportAdr, getAdr, type AdrResponse } from "../api/adrs";
import { ApiError } from "../api/http";
import { useAdmin } from "../auth";
import AdrContentSections from "../components/AdrContentSections";
import AdrEditForm from "../components/AdrEditForm";
import AdrExportButton from "../components/AdrExportButton";
import AdrMainPane, { type AdrTab } from "../components/AdrMainPane";
import AdrSidePanel from "../components/AdrSidePanel";
import AdrSupersededBanner from "../components/AdrSupersededBanner";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EditPageLoadState from "../components/EditPageLoadState";
import PageHeader from "../components/PageHeader";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { adrsPath } from "../utils/adrLinks";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";

/**
 * One ADR (`/adrs/:id`). The main pane is four tabs - the five Markdown sections (or their editor while editing), the discussion, the
 * issues and the lifecycle diagram (`AdrMainPane`); the side panel holds Status, DACI, Details and History. What the signed-in user may do comes from the server's verdicts on the ADR (`allowedTransitions`, `canEditContent`,
 * `canEditExpiry`, `canManageParticipants`, ...); only an ADMIN deletes. Every write refreshes the `["adrs"]` prefix, so the page, the history and the list
 * follow, and each write carries the revision the page holds.
 */
export default function AdrDetails() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const admin = useAdmin();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const [editing, setEditing] = useState(false);
  // The main pane's tab lives here so Edit can bring the document forward (and a tab switch never costs a draft).
  const [tab, setTab] = useState<AdrTab>("document");
  // Set once the ADR is deleted: the page is on its way out and must not fetch the 404 of the ADR it just removed.
  const [deleted, setDeleted] = useState(false);
  const adr = useQuery({ queryKey: ["adrs", "detail", id], queryFn: () => getAdr(id), enabled: Number.isInteger(id) && id > 0 && !deleted });

  const remove = useDeleteConfirm<AdrResponse>({
    mutationFn: (row) => deleteAdr(row.id),
    onSuccess: async (row) => {
      // Stop reading the ADR, leave, refresh the list and drop the deleted ADR's own queries (nothing observes them any more).
      setDeleted(true);
      navigate(adrsPath);
      await refreshQueriesAfterMutation(queryClient, ["adrs", "list"]);
      queryClient.removeQueries({ queryKey: ["adrs", "detail", row.id] });
      queryClient.removeQueries({ queryKey: ["adrs", "events", row.id] });
    },
    successMessage: t("adrs.toast.deleted"),
  });

  if (adr.isLoading || adr.isError || !adr.data) {
    const notFound = !Number.isInteger(id) || id <= 0 || (adr.error instanceof ApiError && adr.error.status === 404);
    return (
      <EditPageLoadState
        isLoading={adr.isLoading}
        message={notFound ? t("adrs.notFound") : loadErrorMessage(adr.error, t)}
        backTo={adrsPath}
        backLabel={t("adrs.backToList")}
        title={t("adrs.title")}
      />
    );
  }
  const data = adr.data;
  // Falls back to the sections the moment the verdict turns (a move to ACTIVE froze the content under an open editor).
  const mayEdit = data.canEditContent;

  return (
    <Stack gap="md">
      <PageHeader
        title={data.name}
        documentTitle={`${data.key} · ${data.name}`}
        description={data.key}
        backTo={{ to: adrsPath, label: t("adrs.backToList") }}
        actions={
          <>
            <AdrExportButton label={t("adrs.export.markdown")} run={() => exportAdr(data.id)} />
            {mayEdit && !editing && (
              <Button variant="default" leftSection={<IconPencil size={16} />} onClick={() => {
                  setTab("document");
                  setEditing(true);
                }}>
                {t("adrs.edit")}
              </Button>
            )}
            {admin && (
              <Button variant="light" color="red" leftSection={<IconTrash size={16} />} onClick={() => remove.requestDelete(data)}>
                {t("common.action.delete")}
              </Button>
            )}
          </>
        }
      />
      <AdrSupersededBanner adr={data} />
      <Grid gap="lg">
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <AdrMainPane adr={data} tab={tab} onTabChange={setTab}>
            {editing && mayEdit ? <AdrEditForm adr={data} onClose={() => setEditing(false)} /> : <AdrContentSections adr={data} />}
          </AdrMainPane>
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 4 }}>
          <AdrSidePanel adr={data} />
        </Grid.Col>
      </Grid>
      <ConfirmDeleteModal
        confirm={remove}
        title={t("adrs.deleteTitle")}
        errorTitle={t("adrs.deleteFailed")}
        errorMessage={(err) => saveErrorMessage(err, t, { tooManyRequests: "common.error.tooManyRequests", forbidden: "adrs.error.forbidden", notFound: "adrs.error.gone", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })}
        body={(target) => t("adrs.deleteBody", { key: target.key, name: target.name })}
      />
    </Stack>
  );
}
