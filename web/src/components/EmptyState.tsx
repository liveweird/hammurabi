import { Center, Stack, Text, ThemeIcon } from "@mantine/core";
import type { Icon } from "@tabler/icons-react";

/**
 * The shared zero-rows body (icon + dimmed one-liner). Takes the Tabler icon COMPONENT and sizes/strokes it itself, so every empty state
 * draws the same glyph weight. `hint` is the optional next step under the label (what to change or do), for an empty state that is more
 * than the absence. `compact` is the same body for a card, a side panel or a drawer rather than a whole table: a smaller glyph and less
 * air around it.
 */
export default function EmptyState({
  icon: Icon,
  label,
  hint,
  compact = false,
}: {
  icon: Icon;
  label: string;
  hint?: string;
  compact?: boolean;
}) {
  return (
    <Center py={compact ? "md" : 48}>
      <Stack align="center" gap={compact ? "xs" : "sm"}>
        <ThemeIcon variant="light" color="gray" size={compact ? 40 : 64} radius="xl">
          <Icon size={compact ? 20 : 32} stroke={1.2} />
        </ThemeIcon>
        <Text c="dimmed" size="sm" ta="center">
          {label}
        </Text>
        {hint && (
          <Text c="dimmed" size="xs" ta="center">
            {hint}
          </Text>
        )}
      </Stack>
    </Center>
  );
}
