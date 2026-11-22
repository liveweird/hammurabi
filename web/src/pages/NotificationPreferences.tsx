import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Box, Button, Group, Stack, Switch, Table, Text } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getNotificationPreferences,
  setNotificationPreferences,
  type NotificationPreferences as Preferences,
  type NotificationType,
} from "../api/notifications";
import { getUserId } from "../api/session";
import ErrorAlert from "../components/ErrorAlert";
import FormErrorAlert from "../components/FormErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import WorkspaceTable from "../components/WorkspaceTable";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import classes from "../theme.module.css";
import styles from "./NotificationPreferences.module.css";

/** The kinds this build words, in the order the server lists them; a newer server's kind is not shown (and its stored switches are kept on save). */
const KNOWN_TYPES: ReadonlySet<string> = new Set<NotificationType>([
  "ROLE_ASSIGNED",
  "ROLE_REMOVED",
  "STATUS_CHANGED",
  "DISCUSSION_POSTED",
  "DISCUSSION_SETTLED",
  "ADR_EXPIRED",
  "ADR_SUPERSEDED",
  "SUCCESSOR_LINKED",
  "SUCCESSOR_UNLINKED",
  "EXPIRY_REMINDER",
  "EXPIRY_CHANGED",
  "SUCCESSOR_ACCEPTED",
  "PREDECESSOR_ENDED",
]);

type Overrides = Partial<Record<NotificationType, boolean>>;

/**
 * The caller's own notification preferences (`GET`/`PUT /users/{id}/notification-preferences`): one row per kind of notice with two switches,
 * the bell and the email. Email needs the bell (a row whose bell is off has its email switch off and disabled), and a deployment with no outbound
 * email says so and disables every email switch. Save is a wholesale replace in the shape the GET answered (every kind with its two switches); the email choice of a
 * row whose bell is off is stored as it was, so it comes back when the bell does.
 */
export default function NotificationPreferences() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const userId = getUserId();
  // The unsaved toggles; the effective state derives from the loaded set + these, so no effect-driven seeding is needed.
  const [bellOverrides, setBellOverrides] = useState<Overrides>({});
  const [emailOverrides, setEmailOverrides] = useState<Overrides>({});
  const [error, setError] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ["notificationPreferences", userId],
    queryFn: () => getNotificationPreferences(userId as number),
    enabled: userId !== null,
  });
  const all = query.data?.kinds ?? [];
  const kinds = all.filter((k) => KNOWN_TYPES.has(k.type));
  const bellOn = (k: Preferences["kinds"][number]) => bellOverrides[k.type] ?? k.inApp;
  const emailOn = (k: Preferences["kinds"][number]) => emailOverrides[k.type] ?? k.email;
  const dirty = kinds.some((k) => bellOn(k) !== k.inApp || emailOn(k) !== k.email);

  const save = useMutation({
    mutationFn: () => {
      // Every kind the server listed goes back (one a newer build words but this one does not keeps its read switches).
      return setNotificationPreferences(
        userId as number,
        all.map((k) => ({ type: k.type, inApp: bellOn(k), email: emailOn(k) })),
      );
    },
    onMutate: () => setError(null),
    onSuccess: async () => {
      // Re-read first, then drop the toggles: the switches never flash back to the old stored state.
      await queryClient.invalidateQueries({ queryKey: ["notificationPreferences", userId] });
      setBellOverrides({});
      setEmailOverrides({});
      showSuccessToast(t("notifications.toast.preferencesSaved"));
    },
    onError: (err) =>
      setError(
        saveErrorMessage(err, t, {
          failedStatus: "notifications.preferences.saveFailedStatus",
          failed: "notifications.preferences.saveFailedNetwork",
        }),
      ),
  });

  return (
    <Stack gap="md">
      <PageHeader title={t("notifications.preferences.title")} description={t("notifications.preferences.description")} />
      {query.isLoading ? (
        <LoadingBlock />
      ) : query.isError || query.data === undefined ? (
        <ErrorAlert error={query.error} />
      ) : (
        <Box maw={CONTENT_MAX_WIDTH} className={styles.preferences}>
          <Stack>
            {!query.data.emailAvailable && (
              <Alert color="gray" variant="light" role="note">
                {t("notifications.preferences.emailOff")}
              </Alert>
            )}
            <WorkspaceTable label={t("notifications.preferences.tableAria")} minWidth={0}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th scope="col">{t("notifications.preferences.column.kind")}</Table.Th>
                  <Table.Th scope="col" className={styles.channel}>{t("notifications.preferences.column.bell")}</Table.Th>
                  <Table.Th scope="col" className={styles.channel}>{t("notifications.preferences.column.email")}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {kinds.map((k) => {
                  const kind = t(`notifications.preferences.kind.${k.type}.label`);
                  const bell = bellOn(k);
                  return (
                    <Table.Tr key={k.type}>
                      <Table.Th scope="row" className={`${classes.breakAnywhere} ${styles.kind}`}>
                        <Text size="sm" fw={600}>
                          {kind}
                        </Text>
                        <Text size="xs" c="dimmed">
                          {t(`notifications.preferences.kind.${k.type}.description`)}
                        </Text>
                      </Table.Th>
                      <Table.Td className={styles.channel}>
                        <Switch
                          aria-label={t("notifications.preferences.bellAria", { kind })}
                          checked={bell}
                          onChange={(e) => {
                            const value = e.currentTarget.checked;
                            setBellOverrides((prev) => ({ ...prev, [k.type]: value }));
                          }}
                        />
                      </Table.Td>
                      <Table.Td className={styles.channel}>
                        <Switch
                          aria-label={t("notifications.preferences.emailAria", { kind })}
                          checked={bell && emailOn(k)}
                          disabled={!query.data.emailAvailable || !bell}
                          onChange={(e) => {
                            const value = e.currentTarget.checked;
                            setEmailOverrides((prev) => ({ ...prev, [k.type]: value }));
                          }}
                        />
                      </Table.Td>
                    </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </WorkspaceTable>
            <Text c="dimmed" size="sm">
              {t("notifications.preferences.emailNeedsBell")}
            </Text>
            {error && <FormErrorAlert>{error}</FormErrorAlert>}
            <Group justify="flex-end" gap="sm">
              <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!dirty}>
                {t("common.action.save")}
              </Button>
            </Group>
          </Stack>
        </Box>
      )}
    </Stack>
  );
}
