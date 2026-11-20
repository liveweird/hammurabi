import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import ClearableTextInput from "./ClearableTextInput";
import { renderWithProviders, screen } from "../test/render";

describe("ClearableTextInput", () => {
  test("typing reports the value and the clear button empties it", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<ClearableTextInput label="Name" value="abc" onChange={onChange} clearLabel="Clear name filter" />);
    await user.type(screen.getByRole("textbox", { name: "Name" }), "d");
    expect(onChange).toHaveBeenLastCalledWith("abcd");
    await user.click(screen.getByRole("button", { name: "Clear name filter" }));
    expect(onChange).toHaveBeenLastCalledWith("");
  });

  test("an empty value shows no clear button", () => {
    renderWithProviders(<ClearableTextInput label="Name" value="" onChange={() => {}} clearLabel="Clear name filter" />);
    expect(screen.queryByRole("button", { name: "Clear name filter" })).not.toBeInTheDocument();
  });
});
