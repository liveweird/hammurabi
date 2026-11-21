import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { AdrResponse } from "../api/adrs";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";

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

/** The ADR's identity and history timestamps. Its expiry date appears under Validity. */
export default function AdrDetailsSection({ adr }: { adr: AdrResponse }) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs">
      <Row label={t("adrs.details.key")}>{adr.key}</Row>
      <Row label={t("adrs.details.created")}>{formatDateTime(adr.createdAt, browserTimeZone())}</Row>
      <Row label={t("adrs.details.updated")}>{formatDateTime(adr.updatedAt, browserTimeZone())}</Row>
      <Row label={t("adrs.details.statusChanged")}>{formatDateTime(adr.statusChangedAt, browserTimeZone())}</Row>
    </Stack>
  );
}
