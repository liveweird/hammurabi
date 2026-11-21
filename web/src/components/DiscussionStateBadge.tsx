import { Badge } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { DiscussionState } from "../api/adrDiscussion";
import { DISCUSSION_STATE_COLOR } from "../utils/adrDiscussion";

/** A question's or issue's state (open, answered, resolved) as a light badge in its one colour and its localized name. */
export default function DiscussionStateBadge({ state }: { state: DiscussionState }) {
  const { t } = useTranslation();
  return (
    <Badge color={DISCUSSION_STATE_COLOR[state]} variant="light" style={{ flexShrink: 0 }}>
      {t(`adrs.discussion.state.${state}`)}
    </Badge>
  );
}
