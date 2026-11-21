import { Button, Group, Paper, Stack, Text, Title } from "@mantine/core";
import { IconGavel, IconPlus, IconScale, IconUser } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import PageHeader from "../components/PageHeader";
import { adrsPath, decisionLogPath, myAdrsPath, newAdrPath } from "../utils/adrLinks";
import { FORM_MAX_WIDTH } from "../utils/layout";

/** The landing page: a welcome that says what Hammurabi is and leads into the decision log and the ADRs — all of them, the user's own, or a new one. */
export default function Home() {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      <PageHeader title={t("home.title")} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH} component="section" aria-labelledby="home-welcome">
        <Stack gap="md">
          <Title order={3} size="h4" id="home-welcome">
            {t("home.welcome.title")}
          </Title>
          <Text size="sm">{t("home.welcome.body")}</Text>
          <Text size="sm" c="dimmed">
            {t("home.welcome.daci")}
          </Text>
          <Group gap="sm">
            <Button component={RouterLink} to={adrsPath} leftSection={<IconGavel size={16} />}>
              {t("home.welcome.browse")}
            </Button>
            <Button component={RouterLink} to={decisionLogPath} variant="default" leftSection={<IconScale size={16} />}>
              {t("home.welcome.log")}
            </Button>
            <Button component={RouterLink} to={newAdrPath} variant="default" leftSection={<IconPlus size={16} />}>
              {t("home.welcome.raise")}
            </Button>
            <Button component={RouterLink} to={myAdrsPath} variant="default" leftSection={<IconUser size={16} />}>
              {t("home.welcome.mine")}
            </Button>
          </Group>
        </Stack>
      </Paper>
    </Stack>
  );
}
