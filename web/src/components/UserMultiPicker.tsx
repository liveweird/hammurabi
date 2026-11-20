import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Group, Stack, Text } from "@mantine/core";
import { IconX } from "@tabler/icons-react";
import type { PickedOption } from "./SearchPicker";
import UserDirectorySelect from "./UserDirectorySelect";

/**
 * A list of people built on the user directory picker: each pick joins the list (shown by name, removable) and the search box empties
 * for the next one. `exclude` holds everyone who may not be offered (the other seats and the people already in this list).
 */
export default function UserMultiPicker({
  label,
  description,
  picks,
  onChange,
  exclude,
}: {
  label: string;
  description?: string;
  picks: PickedOption[];
  onChange: (picks: PickedOption[]) => void;
  exclude: ReadonlySet<string>;
}) {
  const { t } = useTranslation();
  // The picker keeps the label of its last choice in the box; a fresh instance per pick empties it.
  const [generation, setGeneration] = useState(0);
  const taken = new Set([...exclude, ...picks.map((pick) => pick.value)]);

  return (
    <Stack gap={6}>
      <UserDirectorySelect
        key={generation}
        label={label}
        description={description}
        picked={null}
        exclude={taken}
        onPick={(pick) => {
          if (pick === null) return;
          onChange([...picks, pick]);
          setGeneration((g) => g + 1);
        }}
      />
      {picks.length > 0 && (
        <Stack gap={4} role="list" aria-label={t("adrs.daci.listAria", { role: label })}>
          {picks.map((pick) => (
            <Group key={pick.value} gap="xs" wrap="nowrap" role="listitem">
              <Text size="sm" style={{ overflowWrap: "anywhere" }}>
                {pick.label}
              </Text>
              <ActionIcon
                size="sm"
                aria-label={t("adrs.daci.removeAria", { name: pick.label })}
                onClick={() => onChange(picks.filter((other) => other.value !== pick.value))}
              >
                <IconX size={14} />
              </ActionIcon>
            </Group>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
