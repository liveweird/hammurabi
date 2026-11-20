import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Button, Group, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getUserId } from "../api/session";
import type { AdrResponse } from "../api/adrs";
import { ADR_ROLES } from "../utils/adrStatus";
import AdrParticipantsEditor from "./AdrParticipantsEditor";

/**
 * The DACI section: the participants by role (Driver, Approver, Contributors, Informed), the caller marked as "You"; whoever the
 * server says may manage them (`canManageParticipants`) gets the Edit button that swaps the list for the editor.
 */
export default function AdrParticipantsPanel({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  const me = getUserId();
  const [editing, setEditing] = useState(false);

  if (editing) return <AdrParticipantsEditor adr={adr} onClose={() => setEditing(false)} />;
  return (
    <Stack gap="xs">
      {ADR_ROLES.map((role) => {
        const people = adr.participants.filter((participant) => participant.role === role);
        return (
          <div key={role}>
            <Text size="xs" c="dimmed">
              {t(`adrs.roleGroup.${role}`)}
            </Text>
            {people.length === 0 ? (
              <Text size="sm" c="dimmed">
                {role === "APPROVER" ? t("adrs.daci.noApprover") : t("adrs.daci.none")}
              </Text>
            ) : (
              people.map((person) => (
                <Group key={person.userId} gap={6} wrap="nowrap">
                  <Text size="sm" style={{ overflowWrap: "anywhere" }}>
                    {person.name}
                  </Text>
                  {person.userId === me && (
                    <Badge size="xs" variant="light">
                      {t("adrs.daci.you")}
                    </Badge>
                  )}
                </Group>
              ))
            )}
          </div>
        );
      })}
      {adr.canManageParticipants && (
        <Button
          variant="default"
          size="xs"
          style={{ alignSelf: "flex-start" }}
          leftSection={<IconUsers size={14} />}
          onClick={() => setEditing(true)}
        >
          {t("adrs.daci.edit")}
        </Button>
      )}
    </Stack>
  );
}
