import { Badge } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { DiscussionStatus } from "../api/adrDiscussion";
import { DISCUSSION_STATUS_COLOR } from "../utils/adrDiscussion";

/** A question's or issue's state (open, answered, resolved) as a light badge in its one colour and its localized name. */
export default function DiscussionStatusBadge({ status }: { status: DiscussionStatus }) {
  const { t } = useTranslation();
  return (
    <Badge color={DISCUSSION_STATUS_COLOR[status]} variant="light" style={{ flexShrink: 0 }}>
      {t(`adrs.discussion.status.${status}`)}
    </Badge>
  );
}
