import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack, Text } from "@mantine/core";
import { IconCalendarEvent } from "@tabler/icons-react";
import type { AdrResponse } from "../api/adrs";
import AdrExpiryEditor from "./AdrExpiryEditor";

type Mode = "edit" | "extend";

export default function AdrValidity({ adr, headingId }: { adr: AdrResponse; headingId: string }) {
  const { t } = useTranslation();
  const [editor, setEditor] = useState<{ mode: Mode; snapshot: AdrResponse } | null>(null);
  const opener = useRef<Mode | null>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const extendButton = useRef<HTMLButtonElement>(null);
  const active = adr.status === "ACTIVE";
  const shown = editor?.snapshot ?? adr;

  useEffect(() => {
    if (editor !== null || opener.current === null) return;
    const button = opener.current === "extend" ? extendButton.current : editButton.current;
    (button ?? document.getElementById(headingId))?.focus();
    opener.current = null;
  }, [editor, headingId]);

  function open(next: Mode) {
    opener.current = next;
    setEditor({ mode: next, snapshot: adr });
  }

  return (
    <Stack gap="xs">
      {editor?.mode !== "edit" && (
        <div>
          {shown.expiresOn !== null ? (
            <>
              <Text size="xs" c="dimmed">{t(shown.status === "ACTIVE" ? "adrs.validity.lastDay" : "adrs.details.expiresOn")}</Text>
              <Text component="time" dateTime={shown.expiresOn} size="sm" fw={600}>{shown.expiresOn}</Text>
            </>
          ) : (
            <Text size="sm" c="dimmed">{t("adrs.details.noExpiry")}</Text>
          )}
        </div>
      )}
      {editor !== null ? (
        <AdrExpiryEditor
          original={editor.snapshot}
          extending={editor.mode === "extend"}
          canSave={adr.canEditExpiry && (editor.mode === "edit" || active)}
          onClose={() => setEditor(null)}
        />
      ) : adr.canEditExpiry && (
        <Group gap="xs">
          {active && adr.expiresOn !== null && (
            <Button ref={extendButton} size="xs" variant="light" leftSection={<IconCalendarEvent size={14} aria-hidden />} onClick={() => open("extend")}>
              {t("adrs.validity.extend")}
            </Button>
          )}
          <Button ref={editButton} size="xs" variant="default" onClick={() => open("edit")} aria-label={t("adrs.details.editExpiryAria")}>
            {t("common.action.edit")}
          </Button>
        </Group>
      )}
    </Stack>
  );
}
