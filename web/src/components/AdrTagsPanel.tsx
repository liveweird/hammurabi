import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Stack } from "@mantine/core";
import { IconTags } from "@tabler/icons-react";
import type { AdrResponse } from "../api/adrs";
import AdrTagPills from "./AdrTagPills";
import AdrTagsEditor from "./AdrTagsEditor";

/**
 * The Tags section of the side panel: the ADR's pills (a retired tag marked as such), and - for whoever the server says may edit them
 * (`canEditTags`: the Driver, the Approver or an ADMIN, in any non-terminal status, `ACTIVE` included) - the Edit button that swaps them
 * for the grouped picker.
 */
export default function AdrTagsPanel({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  if (editing) return <AdrTagsEditor adr={adr} onClose={() => setEditing(false)} />;
  return (
    <Stack gap="xs">
      <AdrTagPills tags={adr.tags} none={t("adrs.tags.none")} />
      {adr.canEditTags && (
        <Button variant="default" size="xs" style={{ alignSelf: "flex-start" }} leftSection={<IconTags size={14} />} onClick={() => setEditing(true)}>
          {t("adrs.tags.edit")}
        </Button>
      )}
    </Stack>
  );
}
