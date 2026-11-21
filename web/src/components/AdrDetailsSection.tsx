import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Stack, Text } from "@mantine/core";
import { IconCalendarEvent } from "@tabler/icons-react";
import type { AdrResponse } from "../api/adrs";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import AdrExpiryEditor from "./AdrExpiryEditor";

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children}
      </Text>
    </div>
  );
}

/**
 * The Details section: the key, when the ADR was raised, last changed and last moved, and the expiry date (the last day the decision
 * is in force). The date has an editor for whoever the server says may change it (`canEditExpiry`: before the decision is in force the
 * Driver, the Approver or an administrator, once it is the Approver or an administrator), the verdict it applies to the write.
 */
export default function AdrDetailsSection({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  return (
    <Stack gap="xs">
      <Row label={t("adrs.details.key")}>{adr.key}</Row>
      <Row label={t("adrs.details.created")}>{formatDateTime(adr.createdAt, browserTimeZone())}</Row>
      <Row label={t("adrs.details.updated")}>{formatDateTime(adr.updatedAt, browserTimeZone())}</Row>
      <Row label={t("adrs.details.statusChanged")}>{formatDateTime(adr.statusChangedAt, browserTimeZone())}</Row>
      {editing ? (
        <AdrExpiryEditor adr={adr} onClose={() => setEditing(false)} />
      ) : (
        <Group justify="space-between" align="flex-end" wrap="nowrap" gap="xs">
          <Row label={t("adrs.details.expiresOn")}>{adr.expiresOn ?? <Text span c="dimmed">{t("adrs.details.noExpiry")}</Text>}</Row>
          {adr.canEditExpiry && (
            <Button
              size="compact-xs"
              variant="default"
              leftSection={<IconCalendarEvent size={12} />}
              onClick={() => setEditing(true)}
              aria-label={t("adrs.details.editExpiryAria")}
            >
              {t("common.action.edit")}
            </Button>
          )}
        </Group>
      )}
    </Stack>
  );
}
