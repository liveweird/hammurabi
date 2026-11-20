import { Badge } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { AdrStatus } from "../api/adrs";
import { ADR_STATUS_COLOR } from "../utils/adrStatus";

/** An ADR's status as a light badge in its one colour (`ADR_STATUS_COLOR`) and its localized name. */
export default function AdrStatusBadge({ status }: { status: AdrStatus }) {
  const { t } = useTranslation();
  return (
    <Badge color={ADR_STATUS_COLOR[status]} variant="light" style={{ flexShrink: 0 }}>
      {t(`adrs.status.${status}`)}
    </Badge>
  );
}
