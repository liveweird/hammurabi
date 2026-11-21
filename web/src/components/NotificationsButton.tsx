import { useState, useSyncExternalStore, type MouseEvent } from "react";
import { Link as RouterLink, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ActionIcon, Anchor, Box, Button, Drawer, Group, Indicator, Stack, Text, VisuallyHidden } from "@mantine/core";
import { IconBell, IconBellOff, IconChecks } from "@tabler/icons-react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listNotifications, markAllNotificationsSeen, markNotificationSeen, NOTIFICATION_TOTAL_CAP, type NotificationItem } from "../api/notifications";
import { getToken } from "../api/session";
import { useUnseenNotifications } from "../hooks/useUnseenNotifications";
import { adrPath, adrValidityPath } from "../utils/adrLinks";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { badgeText, notificationKeys } from "../utils/notificationKeys";
import { describeNotification } from "../utils/notificationText";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { subscribeLogicalSession } from "../utils/sessionQueryCache";
import CompactPagination from "./CompactPagination";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import LoadingBlock from "./LoadingBlock";

const PAGE_SIZE = 20;

function noticePath(n: Pick<NotificationItem, "adrId" | "type">): string {
  return n.type === "EXPIRY_REMINDER" ? adrValidityPath(n.adrId) : adrPath(n.adrId);
}

function NoticeRow({ n, onOpen }: { n: NotificationItem; onOpen: (n: NotificationItem, event: MouseEvent<HTMLAnchorElement>) => void }) {
  const { t } = useTranslation();
  return (
    <Box component="li" py="sm" px={4} style={{ borderTop: "1px solid var(--mantine-color-default-border)" }}>
      <Anchor component={RouterLink} to={noticePath(n)} onClick={(event: MouseEvent<HTMLAnchorElement>) => onOpen(n, event)} display="block" underline="hover" c="inherit">
        <Group align="flex-start" wrap="nowrap" gap="sm">
          {/* The dot is a shape for sighted users (the brand accent, like the what's-new dot of the account menu: red means blocking, never unread); the hidden word carries "unseen" for the rest. */}
          <Box w={8} pt={6} style={{ flex: "none" }}>
            {!n.seen && <Box aria-hidden="true" data-unseen-dot="" w={8} h={8} bg="hammurabi" style={{ borderRadius: "50%" }} />}
          </Box>
          <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
            {!n.seen && <VisuallyHidden>{t("notifications.unseenRow")}</VisuallyHidden>}
            <Text size="sm" fw={n.seen ? 400 : 600} c={n.seen ? "dimmed" : undefined} style={{ overflowWrap: "anywhere" }}>
              {describeNotification(n, t)}
            </Text>
            <Text size="xs" c="dimmed" style={{ overflowWrap: "anywhere" }}>
              {t("notifications.subline", { key: n.adrKey, name: n.adrName, when: formatDateTime(n.createdAt, browserTimeZone()) })}
            </Text>
          </Stack>
        </Group>
      </Anchor>
    </Box>
  );
}

/**
 * The header bell (Adder's NotificationsButton, ported without its live channel, rooms and threads): a labelled button with the unseen count
 * and a right-hand drawer listing the caller's notices newest first, 20 a page, with "Mark all as seen". The count polls (`useUnseenNotifications`);
 * the drawer's list is read only while it is open. A notice is a link to its ADR: opening it marks it seen (fire-and-forget) and closes the drawer,
 * a modifier-click opens a new tab and leaves everything as it is. No delete, no toast.
 */
export default function NotificationsButton() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const location = useLocation();
  const [opened, setOpened] = useState(false);
  const [returnFocus, setReturnFocus] = useState(true);
  const [page, setPage] = useState(1);
  // The bell outlives a sign-out until the route guard unmounts the shell: meanwhile it keeps no drawer open and reads nothing without a token.
  const signedIn = useSyncExternalStore(subscribeLogicalSession, () => getToken() !== null, () => false);
  const drawerOpen = opened && signedIn;
  const unseen = useUnseenNotifications();

  const listQuery = useQuery({
    queryKey: notificationKeys.list(page),
    queryFn: () => listNotifications({ page, pageSize: PAGE_SIZE }),
    enabled: drawerOpen,
    placeholderData: keepPreviousData,
  });
  const items = listQuery.data?.items ?? [];
  const total = listQuery.data?.total ?? 0;
  // A re-read that emptied the last page (everything on it went) steps back, adjusted during render: to the page before, or to the new last page
  // when more than one page went. Never on a kept-previous placeholder, whose rows belong to another page.
  if (listQuery.data && !listQuery.isPlaceholderData && items.length === 0 && page > 1) setPage(Math.min(page - 1, Math.ceil(total / PAGE_SIZE) || 1));

  const refresh = () => refreshQueriesAfterMutation(queryClient, [...notificationKeys.all]);
  // Settled, not only succeeded: a 404 (the notice is gone) or a 500 (unknown) re-reads, so the rows show what the server holds.
  const markSeen = useMutation({ mutationFn: (id: number) => markNotificationSeen(id), onSettled: refresh });
  const markAll = useMutation({ mutationFn: () => markAllNotificationsSeen(), onSuccess: refresh });

  function open(n: NotificationItem, event: MouseEvent<HTMLAnchorElement>) {
    // Acting on it is "seen"; the navigation does not wait for the write, and a failed write re-reads what the server holds.
    if (!n.seen) markSeen.mutate(n.id);
    // Ctrl/Cmd/Shift/Alt-click opens a new tab or window (the router leaves it to the browser): the drawer and the focus stay where they are.
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    // Keep the destination's focus on a new page or when jumping to Validity on this ADR.
    const adrDestination = adrPath(n.adrId);
    const target = noticePath(n);
    const newSection = target !== adrDestination && target !== location.pathname + location.hash;
    setReturnFocus(adrDestination === location.pathname && !newSection);
    setOpened(false);
  }

  // The badge and the label agree: both say "999+" exactly when the server's total reached its cap (which means "at least 1000").
  const capped = unseen.total >= NOTIFICATION_TOTAL_CAP;
  const countWords = capped ? t("notifications.unseenMany") : t("notifications.unseen", { count: unseen.total });
  // The drawer's own rows count too: the badge query may have failed while the list loaded.
  const anyUnseen = unseen.total > 0 || items.some((n) => !n.seen);
  return (
    <>
      <Indicator label={<span aria-hidden="true">{badgeText(unseen.total)}</span>} color="hammurabi" size={16} offset={4} disabled={unseen.total === 0}>
        <ActionIcon
          variant="subtle"
          color="gray"
          size="lg"
          aria-haspopup="dialog"
          aria-expanded={drawerOpen}
          aria-label={`${t("notifications.title")} (${countWords})`}
          onClick={() => {
            setReturnFocus(true);
            setPage(1);
            markAll.reset();
            setOpened(true);
          }}
        >
          <IconBell size={18} />
        </ActionIcon>
      </Indicator>
      <Drawer
        opened={drawerOpen}
        onClose={() => setOpened(false)}
        returnFocus={returnFocus}
        position="right"
        size="md"
        title={t("notifications.title")}
        closeButtonProps={{ "aria-label": t("common.action.close") }}
      >
        <Stack gap="sm">
          {anyUnseen && (
            <Group justify="flex-end">
              <Button size="xs" variant="light" leftSection={<IconChecks size={14} />} onClick={() => markAll.mutate()} loading={markAll.isPending}>
                {t("notifications.markAllSeen")}
              </Button>
            </Group>
          )}
          {markAll.isError && <FormErrorAlert>{saveErrorMessage(markAll.error, t, { failed: "notifications.markAllFailed" })}</FormErrorAlert>}
          {listQuery.isLoading ? (
            <LoadingBlock />
          ) : listQuery.isError ? (
            <ErrorAlert error={listQuery.error} title={t("notifications.loadFailed")} />
          ) : items.length === 0 ? (
            <EmptyState icon={IconBellOff} label={t("notifications.empty")} />
          ) : (
            <Box component="ul" aria-label={t("notifications.listAria")} m={0} p={0} style={{ listStyle: "none" }}>
              {items.map((n) => (
                <NoticeRow key={n.id} n={n} onOpen={open} />
              ))}
            </Box>
          )}
          <CompactPagination page={page} totalPages={Math.ceil(total / PAGE_SIZE)} onChange={setPage} />
        </Stack>
      </Drawer>
    </>
  );
}
