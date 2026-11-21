import { useEffect } from "react";
import { Stack, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHANGELOG } from "../changelog/entries";
import { markChangelogSeen } from "../hooks/useChangelogSeen";
import MarkdownView from "../components/MarkdownView";
import PageHeader from "../components/PageHeader";
import styles from "./Changelog.module.css";

// Changelog bodies are authored release CONTENT, deliberately not tied to the UI language
// set: entries carry hand-written EN + PL only, and any other shipped UI language reads the
// English body (retro-translating every release per new language will never happen).
const AUTHORED_LANGUAGES = ["en", "pl"] as const;

export default function Changelog() {
  const { t, i18n } = useTranslation();
  const bodyLang = AUTHORED_LANGUAGES.find((l) => l === i18n.resolvedLanguage) ?? "en";

  useEffect(() => {
    markChangelogSeen();
  }, []);

  return (
    <Stack gap="lg" className={styles.page}>
      <PageHeader title={t("changelog.title")} />
      <ol className={styles.releaseList} role="list">
        {CHANGELOG.map((entry) => {
          const headingId = `release-${entry.version.replaceAll(".", "-")}`;
          return (
            <li key={entry.version}>
              <article className={styles.release} aria-labelledby={headingId}>
                <header className={styles.releaseHeader}>
                  <Title order={3} size="h4" id={headingId}>v{entry.version}</Title>
                  <Text component="time" dateTime={entry.date} size="sm" c="dimmed">{entry.date}</Text>
                </header>
                <div className={styles.releaseBody}>
                  <MarkdownView>{entry[bodyLang]}</MarkdownView>
                </div>
              </article>
            </li>
          );
        })}
      </ol>
    </Stack>
  );
}
