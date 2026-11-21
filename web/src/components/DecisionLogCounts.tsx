import { useId } from "react";
import { Anchor, Group, Paper, Stack, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import type { AdrOverview } from "../api/adrs";
import { adrListPath } from "../utils/adrListParams";
import AdrStatusBadge from "./AdrStatusBadge";

/** The count strip: every status as its badge (the list's colours) with how many ADRs hold it, each a link to the list in that status. */
export default function DecisionLogCounts({ counts }: { counts: AdrOverview["counts"] }) {
  const { t } = useTranslation();
  const headingId = useId();
  return (
    <Paper withBorder p="md" component="section" aria-labelledby={headingId}>
      <Stack gap="xs">
        <Title order={3} size="h4" id={headingId}>
          {t("decisionLog.counts.title")}
        </Title>
        <Group component="ul" gap="lg" p={0} m={0} style={{ listStyle: "none" }}>
          {counts.map(({ status, count }) => (
            <li key={status}>
              <Anchor component={RouterLink} to={adrListPath({ status: [status] })} underline="never">
                <Group gap={6} wrap="nowrap">
                  <AdrStatusBadge status={status} />
                  <Text span fw={600} size="sm">
                    {count}
                  </Text>
                </Group>
              </Anchor>
            </li>
          ))}
        </Group>
      </Stack>
    </Paper>
  );
}
