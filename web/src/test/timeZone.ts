import { afterEach, beforeEach, vi } from "vitest";

/**
 * Pins the zone `browserTimeZone()` reports (it reads `Intl.DateTimeFormat().resolvedOptions().timeZone`) for the calling test,
 * so a page's timestamps are asserted against a known non-UTC zone whatever the host's own is. Undo it with the returned spy's
 * `mockRestore()` (or `vi.restoreAllMocks()`) in `afterEach`.
 */
function pinBrowserTimeZone(zone: string) {
  const original = Intl.DateTimeFormat.prototype.resolvedOptions;
  return vi.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockImplementation(function (this: Intl.DateTimeFormat) {
    return { ...original.call(this), timeZone: zone };
  });
}

/**
 * Registers `beforeEach`/`afterEach` hooks that pin `browserTimeZone()` to `zone` for the calling describe block and restore it
 * afterwards. A zone behind UTC such as `America/Los_Angeles` moves a UTC-midnight timestamp onto the previous day, so a test that
 * forgets the zone fails loudly.
 */
export function usePinnedBrowserTimeZone(zone: string): void {
  let spy: ReturnType<typeof pinBrowserTimeZone> | undefined;
  beforeEach(() => {
    spy = pinBrowserTimeZone(zone);
  });
  afterEach(() => {
    spy?.mockRestore();
  });
}
