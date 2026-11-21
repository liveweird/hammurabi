import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { ApiError } from "../api/http";
import { renderWithProviders, screen } from "../test/render";
import { usePinnedBrowserTimeZone } from "../test/timeZone";
import EventTimeline, { type TimelineItem } from "./EventTimeline";

const ITEM: TimelineItem = { key: 1, title: "ADR raised", lines: ["ADR-0001 \"Use X\""], who: "Dana", when: Date.UTC(2026, 10, 1, 9, 30) };

function renderTimeline(props: Partial<React.ComponentProps<typeof EventTimeline>> = {}) {
  return renderWithProviders(
    <EventTimeline
      items={[ITEM]}
      isLoading={false}
      isError={false}
      error={null}
      emptyMessage="No history yet."
      page={1}
      pageSize={10}
      total={1}
      onPageChange={vi.fn()}
      onRetry={vi.fn()}
      {...props}
    />,
  );
}

describe("EventTimeline", () => {
  usePinnedBrowserTimeZone("America/Los_Angeles");

  test("an entry shows its sentence, its lines and a who · when meta line", () => {
    renderTimeline();
    expect(screen.getByText("ADR raised")).toBeInTheDocument();
    expect(screen.getByText('ADR-0001 "Use X"')).toBeInTheDocument();
    expect(screen.getByText("Dana · 2026-11-01 01:30")).toBeInTheDocument();
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  });

  test("loading shows the spinner; an empty history says so", () => {
    renderTimeline({ isLoading: true, items: undefined });
    expect(screen.getByRole("status", { name: "Loading…" })).toBeInTheDocument();
  });

  test("an empty history is a note, not an error", () => {
    renderTimeline({ items: [], total: 0 });
    expect(screen.getByText("No history yet.")).toBeInTheDocument();
    renderTimeline({ items: undefined, total: 0 });
  });

  test("a failed load is the alert with Retry and keeps the pager", async () => {
    const onRetry = vi.fn();
    renderTimeline({ items: undefined, isError: true, error: new ApiError(500, null), total: 25, page: 2, onRetry });
    expect(screen.getByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.queryByText("No history yet.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous page" })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Retry" }));
    expect(onRetry).toHaveBeenCalled();
  });

  test("past one page there is a compact pager whose controls are named; a placeholder page is dimmed and busy", async () => {
    const onPageChange = vi.fn();
    renderTimeline({ total: 25, onPageChange, isPlaceholder: true });
    expect(screen.getByRole("button", { name: "Next page" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous page" })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Next page" }));
    expect(onPageChange).toHaveBeenCalledWith(2);
    expect(document.querySelector("[aria-busy='true']")).not.toBeNull();
  });
});
