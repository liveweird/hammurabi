import { useTranslation } from "react-i18next";
import { Paper, Stack, Text, Title } from "@mantine/core";
import type { AdrResponse } from "../api/adrs";
import { ADR_TEXT_FIELDS } from "../utils/adrForm";
import MarkdownView from "./MarkdownView";

/**
 * The ADR's five Markdown sections in reading order — problem, context, decision, alternatives considered, consequences — each a Paper
 * with an h3. User Markdown renders in the "message" variant, so its headings can never reshape the page's outline; a section nobody
 * has written yet says so.
 */
export default function AdrContentSections({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      {ADR_TEXT_FIELDS.map((field) => (
        <Paper key={field} withBorder p="md" component="section" aria-label={t(`adrs.field.${field}`)}>
          <Stack gap="xs">
            <Title order={3} size="h4">
              {t(`adrs.field.${field}`)}
            </Title>
            {adr[field].trim() === "" ? (
              <Text c="dimmed" size="sm">
                {t("adrs.section.empty")}
              </Text>
            ) : (
              <MarkdownView variant="message">{adr[field]}</MarkdownView>
            )}
          </Stack>
        </Paper>
      ))}
    </Stack>
  );
}
