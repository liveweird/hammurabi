import type { ReactNode } from "react";
import { Anchor, Box, Group, Text } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import type { AdrLogItem } from "../api/adrs";
import { adrPath } from "../utils/adrLinks";

/**
 * One ADR in a decision-log list: the key (monospace) and the name as ONE link to the ADR, then whatever the section says about it (`note`:
 * a date, the Approver, a badge). A list item, so a screen reader announces "list, N items"; the long names wrap under the key. `highlight` tints the row (grape, the app's "waiting" colour) for the caller's own.
 */
export default function AdrLogRow({ item, note, highlight }: { item: Pick<AdrLogItem, "id" | "key" | "name">; note?: ReactNode; highlight?: boolean }) {
  return (
    <Box component="li" p={highlight ? 6 : 0} bg={highlight ? "var(--mantine-color-grape-light)" : undefined} style={{ borderRadius: "var(--mantine-radius-sm)" }}>
      <Group gap="xs" justify="space-between" align="baseline" wrap="nowrap">
        <Anchor component={RouterLink} to={adrPath(item.id)} size="sm" style={{ minWidth: 0 }}>
          <Text span ff="monospace" size="sm" mr={6}>
            {item.key}
          </Text>
          {item.name}
        </Anchor>
        {note && (
          <Group gap="xs" wrap="nowrap" style={{ flexShrink: 0 }}>
            {note}
          </Group>
        )}
      </Group>
    </Box>
  );
}
