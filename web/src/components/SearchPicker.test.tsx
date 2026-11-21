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
  disabled?: boolean;
  error?: string;
  lazy?: boolean;
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

  test("a lazy picker searches only once its dropdown opens", async () => {
    const search = vi.fn(() => Promise.resolve(ROWS));
    const user = userEvent.setup();
    renderWithProviders(<Harness lazy search={search} />);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(search).not.toHaveBeenCalled();
    await user.click(screen.getByRole("combobox", { name: "Thing" }));
    await waitFor(() => expect(search).toHaveBeenCalledWith(""));
    expect(await screen.findByRole("option", { name: "Alpha" })).toBeInTheDocument();
  });

  test("a picker that is not lazy loads its head with the control", async () => {
    const search = vi.fn(() => Promise.resolve(ROWS));
    renderWithProviders(<Harness search={search} />);
    await waitFor(() => expect(search).toHaveBeenCalledWith(""));
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
