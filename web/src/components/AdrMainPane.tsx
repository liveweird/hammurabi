import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Tabs, VisuallyHidden } from "@mantine/core";
import type { AdrResponse } from "../api/adrs";
import { DISCUSSION_KIND_COLOR } from "../utils/adrDiscussion";
import AdrDiscussion from "./AdrDiscussion";

export type AdrTab = "document" | "discussion" | "issues";

/** A count badge: the number is what sighted users read, the visually hidden words are what the tab's accessible name adds. */
function CountBadge({ count, color, label }: { count: number; color: string; label: string }) {
  return (
    <Badge size="sm" circle color={color} variant="light">
      <span aria-hidden>{count}</span>
      <VisuallyHidden>{label}</VisuallyHidden>
    </Badge>
  );
}

/**
 * The ADR page's main pane: three tabs - the document (its five Markdown sections, or their editor: the caller passes it), the discussion
 * (comments and questions) and the issues (issues and escalations). The active tab belongs to the PAGE (`tab`/`onTabChange`: Edit has to
 * bring the document forward). The discussion and issue tabs carry the counts the ADR serves (unanswered questions, open issues) and stay
 * readable in every status. A tab's panel is first rendered when the tab is first opened (no fetch before) and then STAYS mounted, hidden
 * while another tab is up, so an unsaved document edit, a composer draft and an inline entry editor survive a tab switch.
 */
export default function AdrMainPane({
  adr,
  tab,
  onTabChange,
  children,
}: {
  adr: AdrResponse;
  tab: AdrTab;
  onTabChange: (tab: AdrTab) => void;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  const [visited, setVisited] = useState<ReadonlySet<AdrTab>>(() => new Set([tab]));
  if (!visited.has(tab)) setVisited(new Set([...visited, tab]));

  return (
    <Tabs value={tab} onChange={(next) => next && onTabChange(next as AdrTab)} keepMounted>
      <Tabs.List>
        <Tabs.Tab value="document">{t("adrs.tab.document")}</Tabs.Tab>
        <Tabs.Tab
          value="discussion"
          rightSection={
            adr.unansweredQuestionCount > 0 && (
              <CountBadge count={adr.unansweredQuestionCount} color={DISCUSSION_KIND_COLOR.QUESTION} label={t("adrs.tab.unansweredAria", { count: adr.unansweredQuestionCount })} />
            )
          }
        >
          {t("adrs.tab.discussion")}
        </Tabs.Tab>
        <Tabs.Tab
          value="issues"
          rightSection={
            adr.openIssueCount > 0 && (
              <CountBadge count={adr.openIssueCount} color={DISCUSSION_KIND_COLOR.ISSUE} label={t("adrs.tab.openIssuesAria", { count: adr.openIssueCount })} />
            )
          }
        >
          {t("adrs.tab.issues")}
        </Tabs.Tab>
      </Tabs.List>
      <Tabs.Panel value="document" pt="md">
        {children}
      </Tabs.Panel>
      <Tabs.Panel value="discussion" pt="md">
        {visited.has("discussion") && <AdrDiscussion adr={adr} variant="discussion" />}
      </Tabs.Panel>
      <Tabs.Panel value="issues" pt="md">
        {visited.has("issues") && <AdrDiscussion adr={adr} variant="issues" />}
      </Tabs.Panel>
    </Tabs>
  );
}
