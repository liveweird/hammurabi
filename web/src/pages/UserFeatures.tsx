import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, Navigate, useNavigate, useParams } from "react-router-dom";
import { Box, Button, Group, Stack, Switch, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { FEATURES, type Feature } from "../api/session";
import { useAdmin } from "../auth";
import { getUser, updateUserFeatures } from "../api/users";
import EditPageLoadState from "../components/EditPageLoadState";
import { showSuccessToast } from "../utils/toast";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import PageHeader from "../components/PageHeader";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { usersPath } from "../utils/userLinks";
import FormErrorAlert from "../components/FormErrorAlert";
import styles from "./RegistryViews.module.css";

/**
 * The per-user feature-flags editor (Lettuce's, ported): one switch per feature
 * (checked = enabled), saved as a wholesale replace via PUT /users/{id}/features.
 * ADMIN-only; reached from the users table's Features button.
 */
export default function UserFeatures() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // The admin's unsaved toggles; the effective state derives from the loaded user + these,
  // so no effect-driven seeding is needed. checked = the feature is ENABLED.
  const [overrides, setOverrides] = useState<Partial<Record<Feature, boolean>>>({});

  const idIsValid = Number.isFinite(id) && id > 0;

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["user", id],
    queryFn: () => getUser(id),
    enabled: idIsValid && admin,
    retry: false,
  });

  const enabled: Record<Feature, boolean> | null =
    data == null
      ? null
      : (Object.fromEntries(
          FEATURES.map((f) => [f, overrides[f] ?? !(data.disabledFeatures ?? []).includes(f)]),
        ) as Record<Feature, boolean>);

  if (!idIsValid) return <Navigate to={usersPath} replace />;

  async function onSave() {
    if (enabled == null) return;
    setError(null);
    setSubmitting(true);
    try {
      await updateUserFeatures(id, FEATURES.filter((f) => !enabled[f]));
      await queryClient.invalidateQueries({ queryKey: ["users"] });
      await queryClient.invalidateQueries({ queryKey: ["user", id] });
      showSuccessToast(t("users.toast.featuresSaved"));
      navigate(usersPath, { replace: true });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          notFound: "users.userNotFound",
          failedStatus: "users.featuresFailedStatus",
          failed: "users.featuresFailedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  return (
    <Stack gap="lg" className={styles.page}>
      <PageHeader title={t("users.featuresTitle")} backTo={{ to: usersPath, label: t("users.title") }} />
      <Box className={styles.detailPanel} maw={FORM_MAX_WIDTH}>
        <Stack>
          {isLoading || isError || enabled == null ? (
            <EditPageLoadState
              isLoading={isLoading || (!isError && enabled == null)}
              message={notFound ? t("users.userNotFound") : loadErrorMessage(fetchError, t)}
              backTo={usersPath}
              backLabel={t("users.backToUsers")}
            />
          ) : (
            <Stack>
              {data && (
                <Text c="dimmed" size="sm">
                  {t("users.featuresHint", { name: data.name, email: data.email })}
                </Text>
              )}
              <ul className={styles.featureList}>
                {FEATURES.map((f) => (
                  <li key={f} className={styles.featureRow}>
                    <Switch
                      label={t(`common.feature.${f}`)}
                      description={t(`common.featureHint.${f}`)}
                      checked={enabled[f]}
                      onChange={(e) => {
                        const value = e.currentTarget.checked;
                        setOverrides((prev) => ({ ...prev, [f]: value }));
                      }}
                    />
                  </li>
                ))}
              </ul>
              {error && (
                <FormErrorAlert>
                  {error}
                </FormErrorAlert>
              )}
              <Group justify="flex-end" gap="sm" className={styles.featureActions}>
                <Button component={RouterLink} to={usersPath} variant="default">
                  {t("common.action.cancel")}
                </Button>
                <Button onClick={onSave} loading={submitting}>
                  {t("common.action.save")}
                </Button>
              </Group>
            </Stack>
          )}
        </Stack>
      </Box>
    </Stack>
  );
}
