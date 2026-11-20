import { afterEach, describe, expect, test, vi } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import SearchPicker, { type PickedOption } from "./SearchPicker";
import { renderWithProviders } from "../test/render";

const ROWS: PickedOption[] = [
  { value: "1", label: "Alpha" },
  { value: "2", label: "Beta" },
];

function Harness({
  search = () => Promise.resolve(ROWS),
  initial = null,
  ...rest
}: {
  search?: (term: string) => Promise<PickedOption[]>;
  initial?: PickedOption | null;
  compact?: boolean;
  clearable?: boolean;
  disabled?: boolean;
  error?: string;
}) {
  const [picked, setPicked] = useState<PickedOption | null>(initial);
  return (
    <>
      <SearchPicker
        label="Thing"
        placeholder="Pick"
        clearLabel="Clear thing"
        noMatchLabel="Nothing"
        queryKey={["thing"]}
        search={search}
        picked={picked}
        onPick={setPicked}
        {...rest}
      />
      <button onClick={() => setPicked(null)}>reset</button>
    </>
  );
}

describe("SearchPicker", () => {
  afterEach(() => localStorage.clear());

  test("a compact picker names its control by the label and has no visible caption", async () => {
    renderWithProviders(<Harness compact />);
    expect(screen.getByRole("combobox", { name: "Thing" })).toBeInTheDocument();
    expect(screen.queryByText("Thing")).not.toBeInTheDocument();
  });

  test("a compact picker marks a failed search with the red border instead of an alert", async () => {
    renderWithProviders(<Harness compact search={() => Promise.reject(new Error("boom"))} />);
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Thing" })).toHaveAttribute("aria-invalid", "true"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("a caller's field error wins and a disabled picker cannot be opened", async () => {
    renderWithProviders(<Harness error="Pick something" disabled />);
    expect(screen.getByText("Pick something")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Thing" })).toBeDisabled();
  });

  test("the box follows a pick the caller changed without a choice here, and clear hands back null", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={ROWS[0]} />);
    const box = screen.getByRole("combobox", { name: "Thing" });
    expect(box).toHaveValue("Alpha");
    await user.click(screen.getByRole("button", { name: "reset" }));
    expect(box).toHaveValue("");
    await user.click(box);
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    expect(box).toHaveValue("Beta");
    await user.click(screen.getByLabelText("Clear thing"));
    expect(box).toHaveValue("");
  });

  test("clearable=false offers no clear button", async () => {
    renderWithProviders(<Harness initial={ROWS[1]} clearable={false} />);
    expect(screen.queryByLabelText("Clear thing")).not.toBeInTheDocument();
  });

  test("while a changed term settles the previous rows stay and no 'No matching' is claimed yet", async () => {
    const search = vi.fn((term: string) => Promise.resolve(term === "" ? ROWS : []));
    const user = userEvent.setup();
    renderWithProviders(<Harness search={search} />);
    const box = screen.getByRole("combobox", { name: "Thing" });
    await user.click(box);
    expect(await screen.findByRole("option", { name: "Alpha" })).toBeInTheDocument();
    await user.type(box, "Al");
    // Debounce not elapsed: the head rows (Alpha matches) are still there, nothing is claimed as unmatched.
    expect(screen.getByRole("option", { name: "Alpha" })).toBeInTheDocument();
    expect(screen.queryByText("Nothing")).not.toBeInTheDocument();
    expect(search).toHaveBeenCalledTimes(1);
    // Settled: the (empty) answer for "Al" arrives and only now is "Nothing" stated.
    expect(await screen.findByText("Nothing")).toBeInTheDocument();
    expect(search).toHaveBeenLastCalledWith("Al");
  });

  test("fast typing searches once, for the last term, and renders only its rows", async () => {
    const search = vi.fn((term: string) => Promise.resolve([{ value: "9", label: `Row ${term}` }]));
    const user = userEvent.setup();
    renderWithProviders(<Harness search={search} />);
    const box = screen.getByRole("combobox", { name: "Thing" });
    await user.type(box, "abc");
    expect(await screen.findByRole("option", { name: "Row abc" })).toBeInTheDocument();
    expect(search.mock.calls.map(([term]) => term)).toEqual(["", "abc"]);
    expect(screen.queryByRole("option", { name: "Row a" })).not.toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Row ab" })).not.toBeInTheDocument();
  });
});
