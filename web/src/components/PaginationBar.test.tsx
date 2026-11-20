import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import PaginationBar from "./PaginationBar";
import { renderWithProviders, screen } from "../test/render";

describe("PaginationBar", () => {
  test("shows the total and reports a page and a page-size change", async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    const onPageSizeChange = vi.fn();
    renderWithProviders(
      <PaginationBar total={95} page={1} pageSize={20} onPageChange={onPageChange} onPageSizeChange={onPageSizeChange} />,
    );
    expect(screen.getByText("95 total")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "2" }));
    expect(onPageChange).toHaveBeenCalledWith(2);
    await user.click(screen.getByLabelText("Rows per page", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "40 / page" }));
    expect(onPageSizeChange).toHaveBeenCalledWith(40);
  });
});
