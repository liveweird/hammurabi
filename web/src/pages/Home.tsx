import { Stack } from "@mantine/core";
import { IconMessages } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";

/**
 * The landing page. In v0.1.0 it is a placeholder: rooms and threads (the chat and its workflow
 * threads) arrive in a later version and replace the empty state.
 */
export default function Home() {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      <PageHeader title={t("home.title")} />
      <EmptyState icon={IconMessages} label={t("home.empty.label")} hint={t("home.empty.hint")} />
    </Stack>
  );
}
