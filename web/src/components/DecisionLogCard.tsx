import { useId, type ReactNode } from "react";
import { Group, Paper, Stack, Text, Title } from "@mantine/core";
import type { Icon } from "@tabler/icons-react";

/**
 * One section of the decision log (Astrolabe's `HomeCard` layout): a bordered section named by its h3 with the Tabler icon beside it, one
 * dimmed sentence saying what the section holds, then `children` (a list, an empty line, the "+N more" link).
 */
export default function DecisionLogCard({ icon: CardIcon, title, hint, children }: { icon: Icon; title: string; hint?: string; children: ReactNode }) {
  const headingId = useId();
  return (
    <Paper withBorder p="md" component="section" aria-labelledby={headingId}>
      <Stack gap="xs">
        <Group gap="xs" wrap="nowrap">
          <CardIcon size={20} aria-hidden />
          <Title order={3} size="h4" id={headingId}>
            {title}
          </Title>
        </Group>
        {hint && (
          <Text size="sm" c="dimmed">
            {hint}
          </Text>
        )}
        {children}
      </Stack>
    </Paper>
  );
}

/** A decision-log list: a real `<ul>` of `AdrLogRow`s with no bullets (the rows are the structure), `label` naming it for assistive technology. */
export function LogList({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack component="ul" gap={6} p={0} m={0} aria-label={label} style={{ listStyle: "none" }}>
      {children}
    </Stack>
  );
}
