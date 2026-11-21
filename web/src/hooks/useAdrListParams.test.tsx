import type { ReactNode } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { NO_ADR_FILTERS } from "../utils/adrListParams";
import { useAdrListParams } from "./useAdrListParams";

function setup(route = "/adrs") {
  const wrapper = ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>;
  return renderHook(() => ({ list: useAdrListParams(), location: useLocation() }), { wrapper });
}

describe("useAdrListParams", () => {
  afterEach(() => vi.useRealTimers());

  test("reads the filters from the URL and writes a patch back, keeping the rest, without adding history entries", () => {
    const { result } = setup("/adrs?status=ACTIVE&hasPredecessor=true");
    expect(result.current.list.params).toMatchObject({ status: ["ACTIVE"], hasPredecessor: true });
    act(() => result.current.list.update({ hasOpenIssues: false, createdFrom: "2026-01-01" }));
    expect(result.current.location.search).toBe("?status=ACTIVE&createdFrom=2026-01-01&hasOpenIssues=false&hasPredecessor=true");
    expect(result.current.list.params).toMatchObject({ status: ["ACTIVE"], hasPredecessor: true, hasOpenIssues: false, createdFrom: "2026-01-01" });
  });

  test("a filtered link round-trips: parsing the URL it wrote gives the same filters", () => {
    const first = setup();
    act(() => first.result.current.list.update({ text: "queue", tag: ["data"], participantId: 9, participantRole: "DRIVER", expiresBefore: "2027-01-01", hasSuccessor: true }));
    const search = first.result.current.location.search;
    const second = setup(`/adrs${search}`);
    expect(second.result.current.list.params).toEqual(first.result.current.list.params);
    expect(second.result.current.list.search.value).toBe("queue");
  });

  test("the box follows every keystroke but the URL gets the trimmed term only after the debounce and from two characters", () => {
    vi.useFakeTimers();
    const { result } = setup();
    act(() => result.current.list.search.onChange("a"));
    expect(result.current.list.search.value).toBe("a");
    expect(result.current.list.search.tooShort).toBe(true);
    act(() => void vi.advanceTimersByTime(400));
    expect(result.current.location.search).toBe("");

    act(() => result.current.list.search.onChange(" ab "));
    expect(result.current.list.search.tooShort).toBe(false);
    act(() => void vi.advanceTimersByTime(200));
    expect(result.current.location.search).toBe("");
    act(() => void vi.advanceTimersByTime(200));
    expect(result.current.location.search).toBe("?text=ab");
    expect(result.current.list.search.value).toBe(" ab ");

    act(() => result.current.list.search.onChange(""));
    expect(result.current.list.search.tooShort).toBe(false);
    act(() => void vi.advanceTimersByTime(400));
    expect(result.current.location.search).toBe("");
  });

  test("a term with no letter or digit, or a single astral character, is never sent and hints", () => {
    vi.useFakeTimers();
    const { result } = setup();
    for (const value of ["!!", "--", " .. ", "\u{1D56C}", "\u{1F600}\u{1F600}"]) {
      act(() => result.current.list.search.onChange(value));
      act(() => void vi.advanceTimersByTime(400));
      expect(result.current.location.search, value).toBe("");
      expect(result.current.list.search.tooShort, value).toBe(true);
      expect(result.current.list.search.value).toBe(value);
    }
    // Two astral letters are two characters (code points), though four UTF-16 units; one is one.
    act(() => result.current.list.search.onChange("\u{1D56C}\u{1D56D}"));
    act(() => void vi.advanceTimersByTime(400));
    expect(result.current.list.search.tooShort).toBe(false);
    expect(decodeURIComponent(result.current.location.search)).toBe("?text=\u{1D56C}\u{1D56D}");
  });

  test("cutting the box back to one character clears the committed term, so the rows never belong to a term not shown", () => {
    vi.useFakeTimers();
    const { result } = setup("/adrs?text=queue&status=ACTIVE");
    act(() => result.current.list.search.onChange("q"));
    expect(result.current.location.search).toBe("?text=queue&status=ACTIVE");
    act(() => void vi.advanceTimersByTime(400));
    expect(result.current.location.search).toBe("?status=ACTIVE");
    expect(result.current.list.params.text).toBe("");
    expect(result.current.list.search.value, "the box keeps what was typed").toBe("q");
    expect(result.current.list.search.tooShort).toBe(true);
  });

  test("a change of the URL under the box (a lens, clear all) replaces its text and cancels a pending commit", () => {
    vi.useFakeTimers();
    const { result } = setup("/adrs?text=old");
    expect(result.current.list.search.value).toBe("old");
    act(() => result.current.list.search.onChange("typing"));
    act(() => result.current.list.update({ ...NO_ADR_FILTERS, text: "lens term" }));
    expect(result.current.list.search.value).toBe("lens term");
    act(() => void vi.advanceTimersByTime(500));
    expect(result.current.location.search).toBe("?text=lens+term");
    act(() => result.current.list.update(NO_ADR_FILTERS));
    expect(result.current.list.search.value).toBe("");
    expect(result.current.location.search).toBe("");
  });

  test("a real-timer commit reaches the URL", async () => {
    const { result } = setup();
    act(() => result.current.list.search.onChange("queue"));
    await waitFor(() => expect(result.current.location.search).toBe("?text=queue"));
  });
});
