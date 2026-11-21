import { useState } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, renderWithProviders, screen } from "../test/render";
import MarkdownField from "./MarkdownField";

// The visual editor is lazy and runs Lexical: a stand-in with the same props (MarkdownRichEditor.test.tsx runs the real one). It
// refuses any text starting with "<" the way the real editor refuses raw HTML, so the fallback to the source view can be driven.
vi.mock("./MarkdownRichEditor", async () => {
  const { useEffect } = await import("react");
  return {
    default: function MarkdownRichEditorStub({
      value,
      onChange,
      onUnsupported,
      labelId,
      describedBy,
      required,
      invalid,
      minRows,
    }: {
      value: string;
      onChange: (value: string) => void;
      onUnsupported: () => void;
      labelId: string;
      describedBy?: string;
      required: boolean;
      invalid: boolean;
      minRows: number;
    }) {
      const refused = value.startsWith("<");
      useEffect(() => {
        if (refused) onUnsupported();
      }, [refused, onUnsupported]);
      return (
        <div
          role="textbox"
          aria-label="visual"
          aria-labelledby={labelId}
          aria-describedby={describedBy}
          aria-required={required}
          aria-invalid={invalid}
          data-min-rows={minRows}
          contentEditable
          suppressContentEditableWarning
          onInput={(event) => onChange(event.currentTarget.textContent ?? "")}
        >
          {value}
        </div>
      );
    },
  };
});

function Harness({ initial = "", maxLength = 100, error }: { initial?: string; maxLength?: number; error?: string }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <MarkdownField label="Context" value={value} onChange={setValue} maxLength={maxLength} error={error} description="Why it matters" required minRows={6} />
      <output data-testid="stored">{value}</output>
      <button type="button" onClick={() => setValue("")}>
        Clear
      </button>
    </>
  );
}

afterEach(() => localStorage.clear());

describe("MarkdownField", () => {
  test("opens on the visual editor: the editable region named by the label, required and described", async () => {
    renderWithProviders(<Harness initial="hello" />);
    const box = await screen.findByRole("textbox", { name: /^Context/ });
    expect(box).toHaveTextContent("hello");
    expect(box).toHaveAttribute("aria-required", "true");
    expect(box).toHaveAttribute("aria-invalid", "false");
    expect(box).toHaveAttribute("data-min-rows", "6");
    expect(screen.getByText("Why it matters")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Visual" })).toBeChecked();
  });

  test("what the visual editor reports is the field's Markdown value", async () => {
    renderWithProviders(<Harness initial="" />);
    const box = await screen.findByRole("textbox", { name: /^Context/ });
    box.textContent = "**bold** text";
    act(() => {
      box.dispatchEvent(new InputEvent("input", { bubbles: true }));
    });
    expect(screen.getByTestId("stored")).toHaveTextContent("**bold** text");
  });

  test("the Markdown toggle edits the same text in a textarea, and the visual view shows it again", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={"# Title\n\n**bold** text"} />);
    await screen.findByRole("textbox", { name: /^Context/ });
    await user.click(screen.getByRole("radio", { name: "Markdown" }));
    const source = screen.getByRole("textbox", { name: /^Context/ });
    expect(source.tagName).toBe("TEXTAREA");
    expect(source).toHaveValue("# Title\n\n**bold** text");
    expect(source).toHaveAttribute("aria-required", "true");
    await user.type(source, "!");
    expect(screen.getByTestId("stored")).toHaveTextContent("# Title **bold** text!");
    await user.click(screen.getByRole("radio", { name: "Visual" }));
    expect(await screen.findByRole("textbox", { name: /^Context/ })).toHaveTextContent("text!");
    expect(screen.queryByRole("textbox", { name: /^Context/ })?.tagName).toBe("DIV");
  });

  test("the chosen view is remembered by the next field", async () => {
    const user = userEvent.setup();
    const first = renderWithProviders(<Harness initial="one" />);
    await screen.findByRole("textbox", { name: /^Context/ });
    await user.click(screen.getByRole("radio", { name: "Markdown" }));
    first.unmount();
    renderWithProviders(<Harness initial="two" />);
    expect(screen.getByRole("radio", { name: "Markdown" })).toBeChecked();
    expect(screen.getByRole("textbox", { name: /^Context/ })).toHaveValue("two");
  });

  test("a text the visual editor refuses opens as Markdown with a note, unchanged, and the preference stays", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial="<b>raw</b> html" />);
    expect(await screen.findByRole("note")).toHaveTextContent("formatting the visual editor cannot show");
    const source = screen.getByRole("textbox", { name: /^Context/ });
    expect(source).toHaveValue("<b>raw</b> html");
    expect(screen.getByRole("radio", { name: "Markdown" })).toBeChecked();
    expect(localStorage.getItem("hammurabi.viewSettings.markdownField.mode")).toBeNull();
    // Once the text is something the editor takes, choosing Visual works and the note is gone.
    await user.clear(source);
    await user.type(source, "plain");
    await user.click(screen.getByRole("radio", { name: "Visual" }));
    expect(await screen.findByRole("textbox", { name: /^Context/ })).toHaveTextContent("plain");
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  test("asking for the visual editor on a text it refuses leaves the note up", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial="<b>raw</b>" />);
    await screen.findByRole("note");
    await user.click(screen.getByRole("radio", { name: "Visual" }));
    expect(await screen.findByRole("note")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /^Context/ }).tagName).toBe("TEXTAREA");
  });

  test("a replacement from outside (a posted comment clears its composer) ends the fallback and brings the visual editor back", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial="<b>raw</b>" />);
    await screen.findByRole("note");
    await user.click(screen.getByRole("button", { name: "Clear" }));
    expect(await screen.findByRole("textbox", { name: /^Context/ })).toHaveTextContent("");
    expect(screen.getByRole("textbox", { name: /^Context/ }).tagName).toBe("DIV");
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Visual" })).toBeChecked();
  });

  test("typing in the source view of a refused text keeps the fallback and its note", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial="<b>raw</b>" />);
    await screen.findByRole("note");
    await user.type(screen.getByRole("textbox", { name: /^Context/ }), "!");
    expect(screen.getByRole("note")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /^Context/ })).toHaveValue("<b>raw</b>!");
  });

  test("the counter appears near the limit and the field error shows, on the field", async () => {
    renderWithProviders(<Harness initial={"x".repeat(85)} maxLength={100} error="Too long" />);
    const box = await screen.findByRole("textbox", { name: /^Context/ });
    expect(screen.getByText("85 / 100")).toBeInTheDocument();
    expect(screen.getByText("Too long")).toBeInTheDocument();
    expect(box).toHaveAttribute("aria-invalid", "true");
    const described = box.getAttribute("aria-describedby")?.split(" ") ?? [];
    expect(described.map((id) => document.getElementById(id)?.textContent)).toEqual(["Why it matters", "Too long", "85 / 100"]);
  });

  test("no counter far from the limit", async () => {
    renderWithProviders(<Harness initial="short" maxLength={100} />);
    await screen.findByRole("textbox", { name: /^Context/ });
    expect(screen.queryByText(/\/ 100/)).not.toBeInTheDocument();
  });

  test("text over the limit is kept and counted, never cut: the form reports it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={"x".repeat(100)} maxLength={100} />);
    await screen.findByRole("textbox", { name: /^Context/ });
    await user.click(screen.getByRole("radio", { name: "Markdown" }));
    await user.type(screen.getByRole("textbox", { name: /^Context/ }), "yz");
    expect(screen.getByTestId("stored").textContent).toHaveLength(102);
    expect(screen.getByText("102 / 100")).toBeInTheDocument();
  });
});
