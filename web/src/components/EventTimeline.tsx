import { useTranslation } from "react-i18next";
import { Button, Group, Pagination, Stack, Text, Timeline } from "@mantine/core";
import { formatDateTime } from "../utils/formatDate";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";

// A recorded title or value with no break opportunity (a name, a path) wraps instead of widening the side panel.
const WRAP = { overflowWrap: "anywhere" } as const;

/** One already-rendered history entry: the caller owns the wording, the shell owns the layout. */
export type TimelineItem = {
  key: string | number;
  /** The one localized sentence the entry leads with. */
  title: string;
  /** Dimmed body lines under the title. */
  lines: string[];
  who: string;
  /** Epoch milliseconds, shown as `YYYY-MM-DD HH:mm` (UTC, deterministic like every date). */
  when: number;
};

/**
 * The shared change-history shell (Toadie's/Adder's `EventTimeline`, ported): loading status, load-error alert, empty note, then the
 * newest-first Timeline with a `who · when` meta line per entry and a compact Pagination past one page (it has to fit the side panel's
 * narrow column). A failed load must never masquerade as an empty history, so the alert wins over the note; while a later page loads the
 * previous one stays (dimmed, `aria-busy`); a failed page shows the alert with Retry and keeps the pager, so it is never a dead end.
 */
export default function EventTimeline({
  items,
  isLoading,
  isError,
  error,
  isPlaceholder = false,
  emptyMessage,
  page,
  pageSize,
  total,
  onPageChange,
  onRetry,
}: {
  items: TimelineItem[] | undefined;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  /** The rows shown are the previous page's, kept while the requested one loads. */
  isPlaceholder?: boolean;
  emptyMessage: string;
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  /** Re-asks for the page that failed; the alert offers it as Retry. */
  onRetry: () => void;
}) {
  const { t } = useTranslation();

  // Past one page. Kept on screen when a later page fails to load (the caller passes the last total it knew), so a failed page 2 is never a dead end.
  const pager =
    total > pageSize ? (
      <Group justify="center">
        <Pagination
          size="sm"
          value={page}
          onChange={onPageChange}
          total={Math.ceil(total / pageSize)}
          siblings={0}
          // Icon-only controls need names (WCAG 4.1.2; the axe e2e spec pins it).
          getControlProps={(control) => ({ "aria-label": t(`common.table.${control}Page`) })}
        />
      </Group>
    ) : null;

  if (isLoading) return <LoadingBlock py="sm" />;
  if (isError) {
    return (
      <Stack gap="sm" align="stretch">
        <ErrorAlert error={error} />
        <Button variant="default" size="compact-sm" onClick={onRetry} style={{ alignSelf: "flex-start" }}>
          {t("common.action.retry")}
        </Button>
        {pager}
      </Stack>
    );
  }
  if (!items || items.length === 0) {
    return (
      <Text c="dimmed" size="sm">
        {emptyMessage}
      </Text>
    );
  }

  return (
    <Stack gap="sm" aria-busy={isPlaceholder} style={isPlaceholder ? { opacity: 0.6 } : undefined}>
      <Timeline bulletSize={12} lineWidth={2}>
        {items.map((item) => (
          <Timeline.Item key={item.key} title={<span style={WRAP}>{item.title}</span>}>
            {item.lines.map((line, index) => (
              <Text key={`${index}:${line}`} size="sm" c="dimmed" style={WRAP}>
                {line}
              </Text>
            ))}
            <Text size="xs" c="dimmed">
              {item.who} · {formatDateTime(item.when)}
            </Text>
          </Timeline.Item>
        ))}
      </Timeline>
      {pager}
    </Stack>
  );
}
