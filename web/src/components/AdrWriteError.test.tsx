import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import AdrWriteError from "./AdrWriteError";

const conflict = (type: string) => new ApiError(409, { type, status: 409, title: "Conflict", detail: "d", instance: "/x" });

describe("AdrWriteError", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => localStorage.clear());

  test("an ordinary failure is the red alert in fixed words", () => {
    renderWithProviders(<AdrWriteError error={new ApiError(403, null)} kind="save" onReloaded={vi.fn()} />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Your role on this ADR does not allow this.");
    expect(screen.queryByRole("button", { name: "Reload the ADR" })).not.toBeInTheDocument();
  });

  test("a revision conflict is a notice offering to reload, which refreshes the ADR queries and then calls back", async () => {
    const onReloaded = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    renderWithProviders(<AdrWriteError error={conflict("urn:hammurabi:problem:revision-conflict")} kind="save" onReloaded={onReloaded} />, { queryClient });
    expect(screen.getByRole("alert")).toHaveTextContent("Someone else changed this ADR after you opened it");
    expect(screen.getByText("This ADR has moved on")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Reload the ADR" }));
    await waitFor(() => expect(onReloaded).toHaveBeenCalled());
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["adrs"] });
  });

  test("the frozen and illegal-transition conflicts have their own messages and the same reload", () => {
    const { unmount } = renderWithProviders(<AdrWriteError error={conflict("urn:hammurabi:problem:adr-frozen")} kind="save" onReloaded={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent("This ADR is frozen");
    expect(screen.getByRole("button", { name: "Reload the ADR" })).toBeInTheDocument();
    unmount();
    renderWithProviders(<AdrWriteError error={conflict("urn:hammurabi:problem:illegal-transition")} kind="transition" title="Move failed" onReloaded={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent("This ADR cannot move there from its current status");
    expect(screen.getByText("Move failed")).toBeInTheDocument();
  });
});
