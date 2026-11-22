import { describe, expect, test } from "vitest";
import { renderHook } from "@testing-library/react";
import { useBeforeUnloadGuard } from "./useBeforeUnloadGuard";

/** Fires the browser's unload prompt and reports whether the page asked to stay (the event was cancelled). */
function unloadIsBlocked(): boolean {
  const event = new Event("beforeunload", { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

describe("useBeforeUnloadGuard", () => {
  test("lets the page unload while nothing is dirty", () => {
    renderHook(() => useBeforeUnloadGuard(false));
    expect(unloadIsBlocked()).toBe(false);
  });

  test("asks the browser to confirm while dirty, setting the legacy return value too", () => {
    renderHook(() => useBeforeUnloadGuard(true));
    const event = new Event("beforeunload", { cancelable: true }) as BeforeUnloadEvent;
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(event.returnValue).toBe(true);
  });

  test("follows the dirty flag in both directions", () => {
    const { rerender } = renderHook(({ dirty }) => useBeforeUnloadGuard(dirty), { initialProps: { dirty: false } });
    expect(unloadIsBlocked()).toBe(false);
    rerender({ dirty: true });
    expect(unloadIsBlocked()).toBe(true);
    rerender({ dirty: false });
    expect(unloadIsBlocked()).toBe(false);
  });

  test("detaches its listener when the component unmounts", () => {
    const { unmount } = renderHook(() => useBeforeUnloadGuard(true));
    expect(unloadIsBlocked()).toBe(true);
    unmount();
    expect(unloadIsBlocked()).toBe(false);
  });

  test("attaches one listener however often it re-renders while dirty", () => {
    const { rerender, unmount } = renderHook(() => useBeforeUnloadGuard(true));
    rerender();
    rerender();
    unmount();
    // A listener leaked by a re-render would still block after the unmount.
    expect(unloadIsBlocked()).toBe(false);
  });
});
