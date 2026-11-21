import { Anchor, Button, Stack, Text, Title } from "@mantine/core";
import { IconChevronRight, IconGavel, IconPlus, IconScale, IconUser } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import PageHeader from "../components/PageHeader";
import { adrsPath, decisionLogPath, myAdrsPath, newAdrPath } from "../utils/adrLinks";
import styles from "./Home.module.css";

const destinations = [
  { to: adrsPath, icon: IconGavel, label: "home.welcome.browse" },
  { to: decisionLogPath, icon: IconScale, label: "home.welcome.log" },
  { to: myAdrsPath, icon: IconUser, label: "home.welcome.mine" },
] as const;

/** The landing page: a welcome that says what Hammurabi is and leads into the decision log and the ADRs — all of them, the user's own, or a new one. */
export default function Home() {
  const { t } = useTranslation();
  return (
    <Stack gap="lg" className={styles.page}>
      <PageHeader title={t("home.title")} />
      <section className={styles.welcome} aria-labelledby="home-welcome">
        <div className={styles.introduction}>
          <div className={styles.copy}>
          <Title order={3} size="h4" id="home-welcome">
            {t("home.welcome.title")}
          </Title>
          <Text size="sm">{t("home.welcome.body")}</Text>
          <Text size="sm" c="dimmed">
            {t("home.welcome.daci")}
          </Text>
          </div>
          <div className={styles.primaryAction}>
            <Button component={RouterLink} to={newAdrPath} leftSection={<IconPlus size={16} />}>
              {t("home.welcome.raise")}
            </Button>
          </div>
        </div>
        <nav className={styles.destinationList} aria-label={t("home.welcome.navigation")}>
          {destinations.map(({ to, icon: Icon, label }) => (
            <Anchor key={to} component={RouterLink} to={to} underline="never" className={styles.destination}>
              <span className={styles.destinationIcon}><Icon size={18} stroke={1.7} aria-hidden /></span>
              <span>{t(label)}</span>
              <IconChevronRight size={16} stroke={1.7} aria-hidden className={styles.chevron} />
            </Anchor>
          ))}
        </nav>
      </section>
    </Stack>
  );
}
