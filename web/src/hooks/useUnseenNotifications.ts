import { useQuery } from "@tanstack/react-query";
import { getToken } from "../api/session";
import { listNotifications } from "../api/notifications";
import { notificationKeys } from "../utils/notificationKeys";

/** How often the badge re-reads: Hammurabi has no live channel, so the bell polls (and re-reads on focus and after a write). */
export const UNSEEN_POLL_MS = 60_000;

/**
 * The caller's unseen count for the header badge: ONE row asked for, only its `total` is read (the server caps it at 1000, so a total at the
 * cap means "at least that many"). Polls every minute while the tab is visible (a hidden tab does not), and waits for a signed-in session, so
 * a sign-out never fetches it without a token. The drawer's list has its own query under the same `["notifications"]` prefix.
 */
export function useUnseenNotifications() {
  const query = useQuery({
    queryKey: notificationKeys.unseen,
    queryFn: () => listNotifications({ page: 1, pageSize: 1, seen: false }),
    enabled: getToken() !== null,
    refetchInterval: UNSEEN_POLL_MS,
    refetchIntervalInBackground: false,
  });
  return { ...query, total: query.data?.total ?? 0 };
}
