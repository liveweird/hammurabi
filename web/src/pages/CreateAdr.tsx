import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Button, Group, Paper, Stack, Text, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { IconChevronDown, IconChevronRight } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { createAdr } from "../api/adrs";
import { getUserId } from "../api/session";
import AdrContentFields from "../components/AdrContentFields";
import AdrParticipantsFields from "../components/AdrParticipantsFields";
import FormErrorAlert from "../components/FormErrorAlert";
import PageHeader from "../components/PageHeader";
import { adrFormValidation, ADR_TEXT_FIELDS, EMPTY_ADR_FORM, toCreateBody, type AdrFormValues } from "../utils/adrForm";
import { adrWriteError } from "../utils/adrErrors";
import { adrPath, adrsPath } from "../utils/adrLinks";
import { EMPTY_PARTICIPANTS, type ParticipantsState } from "../utils/adrParticipants";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";

/** Raising an ADR asks for the problem alone; the rest of the content is one click away and can also be written later. */
const FIRST_FIELDS = ["problem"] as const;

/**
 * `/adrs/new`: raise an ADR. Only the problem is required (the name defaults to its first line); the caller becomes the Driver, and
 * the Approver, Contributors and Informed may be named now or later. A name already in use is a field error; success lands on the
 * new ADR's page.
 */
export default function CreateAdr() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const me = getUserId();
  const [moreOpen, setMoreOpen] = useState(false);
  const [participants, setParticipants] = useState<ParticipantsState>(EMPTY_PARTICIPANTS);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const form = useForm<AdrFormValues>({ initialValues: EMPTY_ADR_FORM, validate: adrFormValidation(t, { nameRequired: false }) });

  async function submit(values: AdrFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      const created = await createAdr(toCreateBody(values, participants));
      await refreshQueriesAfterMutation(queryClient, ["adrs"]);
      showSuccessToast(t("adrs.toast.created"));
      navigate(adrPath(created.id), { replace: true });
    } catch (err) {
      // A plain 409 is about THIS control (the name clash); a typed one cannot happen on create.
      if (err instanceof ApiError && err.status === 409) form.setFieldError("name", t("adrs.error.nameTaken"));
      else setError(adrWriteError(err, t, "create"));
      setSubmitting(false);
    }
  }

  return (
    <Stack gap="md">
      <PageHeader title={t("adrs.createTitle")} description={t("adrs.createIntro")} backTo={{ to: adrsPath, label: t("adrs.backToList") }} />
      <Paper withBorder p="xl" maw={CONTENT_MAX_WIDTH}>
        <form onSubmit={form.onSubmit(submit)} noValidate>
          <Stack gap="lg">
            <AdrContentFields form={form} fields={moreOpen ? ADR_TEXT_FIELDS : FIRST_FIELDS} nameRequired={false} />
            <Button
              variant="subtle"
              size="xs"
              style={{ alignSelf: "flex-start" }}
              aria-expanded={moreOpen}
              leftSection={moreOpen ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
              onClick={() => setMoreOpen((open) => !open)}
            >
              {t("adrs.createMore")}
            </Button>
            <Stack gap="xs">
              <Title order={3} size="h4">
                {t("adrs.side.daci")}
              </Title>
              <Text size="sm" c="dimmed">
                {t("adrs.createDaciHint")}
              </Text>
              <AdrParticipantsFields value={participants} onChange={setParticipants} withDriver={false} reserved={me === null ? [] : [String(me)]} />
            </Stack>
            {error && <FormErrorAlert>{error}</FormErrorAlert>}
            <Group justify="flex-end" gap="sm">
              <Button component={RouterLink} to={adrsPath} variant="default">
                {t("common.action.cancel")}
              </Button>
              <Button type="submit" loading={submitting}>
                {t("common.action.create")}
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>
    </Stack>
  );
}
