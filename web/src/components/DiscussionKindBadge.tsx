import { Badge } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { DiscussionKind } from "../api/adrDiscussion";
import { DISCUSSION_KIND_COLOR } from "../utils/adrDiscussion";

/** A discussion entry's kind as a light badge in its one colour (`DISCUSSION_KIND_COLOR`) and its localized name. */
export default function DiscussionKindBadge({ kind }: { kind: DiscussionKind }) {
  const { t } = useTranslation();
  return (
    <Badge color={DISCUSSION_KIND_COLOR[kind]} variant="light" style={{ flexShrink: 0 }}>
      {t(`adrs.discussion.kind.${kind}`)}
    </Badge>
  );
}
