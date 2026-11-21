import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { ApiError } from "./http";
import {
  getNotificationPreferences,
  listNotifications,
  markAllNotificationsSeen,
  markNotificationSeen,
  NOTIFICATION_TOTAL_CAP,
  setNotificationPreferences,
} from "./notifications";

describe("notification API wrappers", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("hammurabi.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const lastCall = () => mockFetch.mock.calls.at(-1) as [string, RequestInit];

  test("the cap is the server's 1000", () => {
    expect(NOTIFICATION_TOTAL_CAP).toBe(1000);
  });

  test("listNotifications pages and sends seen, including false", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 1, total: 0 })));
    await listNotifications({ page: 1, pageSize: 1, seen: false });
    expect(lastCall()[0]).toBe("/api/v1/notifications?page=1&pageSize=1&seen=false");
    await listNotifications({ page: 3, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/notifications?page=3&pageSize=20");
    await listNotifications({ page: 1, pageSize: 20, seen: true });
    expect(lastCall()[0]).toBe("/api/v1/notifications?page=1&pageSize=20&seen=true");
  });

  test("the seen writes are POSTs, and a 404 is an ApiError", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await markNotificationSeen(5);
    expect(lastCall()[0]).toBe("/api/v1/notifications/5/seen");
    expect(lastCall()[1].method).toBe("POST");
    await markAllNotificationsSeen();
    expect(lastCall()[0]).toBe("/api/v1/notifications/seen-all");
    expect(lastCall()[1].method).toBe("POST");
    mockFetch.mockResolvedValue(jsonResponse(404, { title: "Not found", status: 404 }));
    await expect(markNotificationSeen(9)).rejects.toBeInstanceOf(ApiError);
  });

  test("the preferences are read for a user and replaced wholesale with the disabled pairs", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { emailAvailable: true, kinds: [{ type: "ROLE_ASSIGNED", inApp: true, email: false }] }));
    const prefs = await getNotificationPreferences(7);
    expect(prefs.kinds).toEqual([{ type: "ROLE_ASSIGNED", inApp: true, email: false }]);
    expect(lastCall()[0]).toBe("/api/v1/users/7/notification-preferences");
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await setNotificationPreferences(7, [{ type: "ROLE_ASSIGNED", channel: "EMAIL" }]);
    expect(lastCall()[0]).toBe("/api/v1/users/7/notification-preferences");
    expect(lastCall()[1].method).toBe("PUT");
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ disabled: [{ type: "ROLE_ASSIGNED", channel: "EMAIL" }] });
  });
});
