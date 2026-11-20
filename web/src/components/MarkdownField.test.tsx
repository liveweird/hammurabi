import { useState } from "react";
import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import MarkdownField from "./MarkdownField";

function Harness({ initial = "", maxLength = 100, error }: { initial?: string; maxLength?: number; error?: string }) {
  const [value, setValue] = useState(initial);
  return <MarkdownField label="Context" value={value} onChange={setValue} maxLength={maxLength} error={error} description="Why it matters" required />;
}

describe("MarkdownField", () => {
  test("writes into a textarea named by the label", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const box = screen.getByRole("textbox", { name: /Context/ });
    await user.type(box, "**bold**");
    expect(box).toHaveValue("**bold**");
    expect(screen.getByText("Why it matters")).toBeInTheDocument();
  });

  test("the Preview toggle renders the Markdown and Write brings the text back", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={"# Title\n\n**bold** text"} />);
    await user.click(screen.getByRole("radio", { name: "Preview" }));
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByText("bold").tagName).toBe("STRONG");
    // A Markdown heading in user text must not reshape the page outline.
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: "Write" }));
    expect(screen.getByRole("textbox", { name: /Context/ })).toHaveValue("# Title\n\n**bold** text");
  });

  test("an empty preview says there is nothing to preview", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial="  " />);
    await user.click(screen.getByRole("radio", { name: "Preview" }));
    expect(screen.getByText("Nothing to preview.")).toBeInTheDocument();
  });

  test("the character counter appears near the limit and the field error shows", () => {
    renderWithProviders(<Harness initial={"x".repeat(85)} maxLength={100} error="Too long" />);
    expect(screen.getByText("85 / 100")).toBeInTheDocument();
    expect(screen.getByText("Too long")).toBeInTheDocument();
  });

  test("no counter far from the limit", () => {
    renderWithProviders(<Harness initial="short" maxLength={100} />);
    expect(screen.queryByText(/\/ 100/)).not.toBeInTheDocument();
  });
});
