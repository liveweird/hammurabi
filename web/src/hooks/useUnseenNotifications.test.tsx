import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { UNSEEN_POLL_MS, useUnseenNotifications } from "./useUnseenNotifications";
import { jsonResponse } from "../test/http";

describe("useUnseenNotifications", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 1, total: 4 })));
    vi.stubGlobal("fetch", fetchMock);
    localStorage.setItem("hammurabi.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const setup = () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    return renderHook(() => useUnseenNotifications(), { wrapper });
  };

  test("the total of the one-row unseen read is the count", async () => {
    const { result } = setup();
    expect(result.current.total).toBe(0);
    await waitFor(() => expect(result.current.total).toBe(4));
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/notifications?page=1&pageSize=1&seen=false");
  });

  test("it waits for a signed-in session", async () => {
    localStorage.clear();
    const { result } = setup();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.total).toBe(0);
  });

  test("it polls every minute", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { result } = setup();
    await waitFor(() => expect(result.current.total).toBe(4));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 1, total: 6 })));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNSEEN_POLL_MS + 100);
    });
    await waitFor(() => expect(result.current.total).toBe(6));
    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2);
  });
});
