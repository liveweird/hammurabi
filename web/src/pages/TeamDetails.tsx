import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams } from "react-router-dom";
import { Button, Paper, Stack, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { IconPencil } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { useAdmin } from "../auth";
import { getTeam } from "../api/teams";
import EditPageLoadState from "../components/EditPageLoadState";
import PageHeader from "../components/PageHeader";
import TeamEditorModal from "../components/TeamEditorModal";
import { loadErrorMessage } from "../utils/saveError";
import { teamsPath } from "../utils/teamLinks";
import { PANEL_PADDING, WIDE_MAX_WIDTH } from "../utils/layout";
import classes from "../theme.module.css";

/**
 * One team (`/teams/:id`): its name, description and manager. Everyone reads it, its manager included; an ADMIN
 * edits the team (name, description, manager).
 */
export default function TeamDetails() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const queryClient = useQueryClient();
  const admin = useAdmin();

  const team = useQuery({ queryKey: ["teams", "detail", id], queryFn: () => getTeam(id), enabled: Number.isFinite(id) });
  const [editing, setEditing] = useState(false);

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ["teams"] });
  }

  if (team.isLoading || team.isError || !team.data) {
    const notFound = team.error instanceof ApiError && team.error.status === 404;
    return (
      <EditPageLoadState
        isLoading={team.isLoading}
        message={notFound ? t("teams.notFound") : loadErrorMessage(team.error, t)}
        backTo={teamsPath}
        backLabel={t("teams.backToTeams")}
        title={t("teams.title")}
      />
    );
  }
  const data = team.data;

  return (
    <Stack gap="lg" maw={WIDE_MAX_WIDTH}>
      <PageHeader
        title={data.name}
        backTo={{ to: teamsPath, label: t("teams.backToTeams") }}
        actions={
          admin && (
            <Button variant="default" leftSection={<IconPencil size={16} />} onClick={() => setEditing(true)}>
              {t("common.action.edit")}
            </Button>
          )
        }
      />

      <Paper component="section" withBorder radius="md" p={PANEL_PADDING} maw="48rem" className={classes.surface} aria-label={data.name}>
        <Stack gap="lg">
          {data.description && (
            <Stack gap={4}>
              <Text fz="xs" fw={600} c="dimmed">{t("common.field.description")}</Text>
              <Text className={classes.breakWord}>{data.description}</Text>
            </Stack>
          )}
          <Text className={classes.breakWord} c={data.managerName ? undefined : "dimmed"}>
            {data.managerName ? t("teams.managerLine", { name: data.managerName }) : t("teams.noManager")}
          </Text>
        </Stack>
      </Paper>

      {editing && (
        <TeamEditorModal
          target={data}
          onClose={() => setEditing(false)}
          onSaved={async () => {
            setEditing(false);
            await refresh();
          }}
        />
      )}
    </Stack>
  );
}
