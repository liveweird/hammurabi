import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import CompactPagination from "./CompactPagination";

describe("CompactPagination", () => {
  test("renders nothing for a single page", () => {
    renderWithProviders(<CompactPagination page={1} totalPages={1} onChange={vi.fn()} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("renders nothing for no pages at all", () => {
    renderWithProviders(<CompactPagination page={1} totalPages={0} onChange={vi.fn()} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("past one page its icon-only controls carry names and report the page picked", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<CompactPagination page={2} totalPages={3} onChange={onChange} />);
    expect(screen.getByRole("button", { name: "Previous page" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(onChange).toHaveBeenLastCalledWith(3);
    await user.click(screen.getByRole("button", { name: "1" }));
    expect(onChange).toHaveBeenLastCalledWith(1);
  });

  test("siblings narrows the window of page buttons around the current page", () => {
    renderWithProviders(<CompactPagination page={5} totalPages={9} onChange={vi.fn()} siblings={0} />);
    expect(screen.getByRole("button", { name: "5" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "4" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "6" })).not.toBeInTheDocument();
  });
});
