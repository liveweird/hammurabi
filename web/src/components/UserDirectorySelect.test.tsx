import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import UserDirectorySelect from "./UserDirectorySelect";
import type { PickedOption } from "./SearchPicker";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const DIRECTORY = {
  items: [
    { id: 7, name: "Mia Manager", email: "mia@example.test" },
    { id: 12, name: "Mia Other", email: "other@example.test" },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};

function Harness({ onChange }: { onChange: (p: PickedOption | null) => void }) {
  const [picked, setPicked] = useState<PickedOption | null>(null);
  return (
    <UserDirectorySelect
      picked={picked}
      onPick={(p) => {
        setPicked(p);
        onChange(p);
      }}
    />
  );
}

describe("UserDirectorySelect", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const directoryCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/users/directory"));

  beforeEach(() => {
    mockFetch = vi.fn((url: string) =>
      Promise.resolve(url.startsWith("/api/v1/users/directory?") ? jsonResponse(200, DIRECTORY) : jsonResponse(404, { title: "x", status: 404 })),
    );
    vi.stubGlobal("fetch", mockFetch);
    // A REGULAR session: the directory is open to every signed-in user (the ADMIN users list is not).
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    localStorage.setItem("hammurabi.auth.roles", "[]");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("searches nothing until the trimmed input has two characters, and the minimum is the box's standing description", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTimeAsync(ms) });
      renderWithProviders(<Harness onChange={vi.fn()} />);
      const box = screen.getByRole("combobox", { name: "User" });
      // Read to a screen reader on focus, not only when the empty dropdown opens.
      expect(box).toHaveAccessibleDescription("Type at least 2 characters to search");
      expect(box).toHaveAttribute("maxlength", "100");
      await user.click(box);
      await user.type(box, "m");
      // Whitespace does not count: "m " trims to one character.
      await user.type(box, " ");
      // Let any (wrongly) pending debounce fire before asserting that nothing was requested.
      await vi.advanceTimersByTimeAsync(450);
      expect(directoryCalls()).toEqual([]);
      expect(screen.queryByRole("option")).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  test("people already on the roster are not offered", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UserDirectorySelect picked={null} onPick={vi.fn()} exclude={new Set(["7"])} />);
    await user.type(screen.getByRole("combobox", { name: "User" }), "mi");
    expect(await screen.findByRole("option", { name: "Mia Other (other@example.test)" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Mia Manager (mia@example.test)" })).not.toBeInTheDocument();
  });

  test("two characters search the directory with page size 20, list name and email, and report the pick", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness onChange={onChange} />);
    await user.type(screen.getByRole("combobox", { name: "User" }), "mi");
    const option = await screen.findByRole("option", { name: "Mia Other (other@example.test)" });
    await user.click(option);
    expect(onChange).toHaveBeenLastCalledWith({ value: "12", label: "Mia Other (other@example.test)" });
    expect(directoryCalls()).toEqual(["/api/v1/users/directory?q=mi&page=1&pageSize=20&sort=name"]);
  });

  test("going back below two characters drops the earlier rows and shows the hint again", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness onChange={vi.fn()} />);
    const box = screen.getByRole("combobox", { name: "User" });
    await user.type(box, "mi");
    expect(await screen.findByRole("option", { name: "Mia Manager (mia@example.test)" })).toBeInTheDocument();
    await user.type(box, "{Backspace}");
    await waitFor(() => expect(screen.queryByRole("option", { name: "Mia Manager (mia@example.test)" })).not.toBeInTheDocument());
    expect(screen.getByRole("combobox", { name: "User" })).toHaveAccessibleDescription("Type at least 2 characters to search");
  });

  test("a search with no hits says so", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 })));
    const user = userEvent.setup();
    renderWithProviders(<Harness onChange={vi.fn()} />);
    await user.type(screen.getByRole("combobox", { name: "User" }), "zz");
    expect(await screen.findByText("No matching users")).toBeInTheDocument();
  });

  test("a failed search renders the inline error alert", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(429, { title: "Too many", status: 429 })));
    const user = userEvent.setup();
    renderWithProviders(<Harness onChange={vi.fn()} />);
    await user.type(screen.getByRole("combobox", { name: "User" }), "mi");
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many requests");
  });
});
