// Notifications API — the caller's own notices (`GET /api/v1/notifications`) and the two "seen" writes (`POST /api/v1/notifications/{id}/seen`,
// `POST /api/v1/notifications/seen-all`, 204, idempotent), plus the per-user preferences (`.../users/{id}/notification-preferences`). A notice
// is ids plus the names the server reads when it answers (`adr-model.md` "Notifications"); there is no delete. Thin wrappers: transport in
// ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type NotificationPage = paths["/api/v1/notifications"]["get"]["responses"]["200"]["content"]["application/json"];
export type NotificationItem = NotificationPage["items"][number];

/** The server caps `total` here (`NotificationPage.total`): a count at the cap means "at least this many". */
export const NOTIFICATION_TOTAL_CAP = 1000;

interface NotificationQuery {
  page: number;
  pageSize: number;
  /** `false` = the unseen ones only; omitted = both. */
  seen?: boolean;
}

/** One page of the caller's notices, newest first (the server's default sort). */
export async function listNotifications(query: NotificationQuery): Promise<NotificationPage> {
  return jsonRequest<NotificationPage>(`/api/v1/notifications?${buildQuery({ ...query })}`);
}

/** Seen again is a 204; someone else's notice and a missing one are the same 404. */
export async function markNotificationSeen(id: number): Promise<void> {
  return voidRequest(`/api/v1/notifications/${id}/seen`, { method: "POST" });
}

export async function markAllNotificationsSeen(): Promise<void> {
  return voidRequest("/api/v1/notifications/seen-all", { method: "POST" });
}

export type NotificationPreferences = paths["/api/v1/users/{id}/notification-preferences"]["get"]["responses"]["200"]["content"]["application/json"];
export type NotificationType = NotificationPreferences["kinds"][number]["type"];
type PreferencesUpdate = paths["/api/v1/users/{id}/notification-preferences"]["put"]["requestBody"]["content"]["application/json"];
export type DisabledNotificationPreference = PreferencesUpdate["disabled"][number];

/** Which kinds of notice reach the user on which channel (self or ADMIN); `emailAvailable` is false on a deployment with no outbound email. */
export async function getNotificationPreferences(userId: number): Promise<NotificationPreferences> {
  return jsonRequest<NotificationPreferences>(`/api/v1/users/${userId}/notification-preferences`);
}

/** A wholesale replace of the SWITCHED-OFF `(type, channel)` pairs (204): an empty list switches everything on. */
export async function setNotificationPreferences(userId: number, disabled: PreferencesUpdate["disabled"]): Promise<void> {
  const body: PreferencesUpdate = { disabled };
  return voidRequest(`/api/v1/users/${userId}/notification-preferences`, { method: "PUT", body: JSON.stringify(body) });
}
