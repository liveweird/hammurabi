import { useState } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import i18n from "../i18n";
import { act, render, screen, within } from "../test/render";
import MarkdownRichEditor from "./MarkdownRichEditor";

// The REAL editor (MDXEditor over Lexical runs under happy-dom): what these prove is what the saved text goes through.

type Props = Partial<Parameters<typeof MarkdownRichEditor>[0]>;

const settle = () =>
  act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 150));
  });

function Field({ value: initial, onChange, onUnsupported, ...rest }: Props & { value: string }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <div id="label">Context</div>
      <MarkdownRichEditor
        value={value}
        onChange={(next) => {
          onChange?.(next);
          setValue(next);
        }}
        onUnsupported={onUnsupported ?? (() => {})}
        labelId="label"
        required={false}
        invalid={false}
        minRows={4}
        {...rest}
      />
      <output data-testid="value">{value}</output>
    </>
  );
}

/**
 * Puts the caret in one of the editor's text runs (the last one, at its end, unless told otherwise: happy-dom has no layout to click
 * into) and types, the way a person would.
 */
async function typeAt(box: HTMLElement, keys: string, at: { run?: "first" | "last"; offset?: number } = {}) {
  const texts = [...box.querySelectorAll("[data-lexical-text]")];
  const node = (at.run === "first" ? texts[0] : texts[texts.length - 1])?.firstChild;
  if (!node) throw new Error("the editor holds no text to type in");
  box.focus();
  const range = document.createRange();
  range.setStart(node, at.offset ?? node.textContent?.length ?? 0);
  range.collapse(true);
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
  document.dispatchEvent(new Event("selectionchange"));
  await userEvent.setup().keyboard(keys);
}

const typeAtEnd = (box: HTMLElement, keys: string) => typeAt(box, keys);

async function mount(props: Props & { value: string }, scheme: "light" | "dark" = "light") {
  const view = render(
    <MantineProvider env="test" forceColorScheme={scheme}>
      <Field {...props} />
    </MantineProvider>,
  );
  const box = await screen.findByRole("textbox", { name: "Context" });
  await settle();
  return { ...view, box };
}

afterEach(async () => {
  await i18n.changeLanguage("en");
});

// Every construct the page's `MarkdownView` renders (GFM and plain), as users write them.
const SUPPORTED: Record<string, string> = {
  "headings, emphasis, strikethrough, inline code": "# Title\n\n## Sub\n\nSome **bold** and *italic* and ~~gone~~ and `code`.",
  "quotes and thematic breaks": "> a quote\n\n---\n\nafter",
  "bullet, numbered and nested lists": "* a\n* b\n\n1. one\n   - nested\n   - more\n2. two",
  "task lists": "- [ ] todo\n- [x] done",
  "a GFM table": "| a | b |\n| - | - |\n| 1 | 2 |",
  "fenced code with and without a language": "```ts\nconst a = 1;\n```\n\n```\nplain\n```",
  "links and autolinks": "see https://example.com/x_y and <https://example.org> and [text](https://a.b \"title\")",
  "a hard line break": "one  \ntwo",
  "an empty field": "",
};

describe("MarkdownRichEditor loads every supported construct without an error, and an unchanged field emits nothing", () => {
  for (const [name, markdown] of Object.entries(SUPPORTED)) {
    test(name, async () => {
      const onChange = vi.fn();
      const onUnsupported = vi.fn();
      await mount({ value: markdown, onChange, onUnsupported });
      expect(onUnsupported).not.toHaveBeenCalled();
      expect(onChange).not.toHaveBeenCalled();
      expect(screen.getByTestId("value")).toHaveTextContent(markdown.replace(/\s+/g, " ").trim());
    });
  }

  test("what the editor would re-write is not written back: the original text stays the value", async () => {
    // The engine reports `* a` as `- a` and turns the bare URL into a link; neither may reach the form.
    const original = "* a\n* b\n\nhttps://example.com/x_y and <https://example.org>";
    const onChange = vi.fn();
    await mount({ value: original, onChange });
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByTestId("value").textContent).toBe(original);
  });
});

describe("MarkdownRichEditor refuses what it cannot represent, without touching it", () => {
  for (const [name, markdown] of Object.entries({
    "raw HTML": "text <b>bold</b> here",
    "an HTML block": "<div>hi</div>\n\ntext",
    "a reference link": "[a][1]\n\n[1]: http://x.y",
    "an image": "![alt](http://x.y/z.png)",
  })) {
    test(name, async () => {
      const onChange = vi.fn();
      const onUnsupported = vi.fn();
      render(
        <MantineProvider env="test">
          <Field value={markdown} onChange={onChange} onUnsupported={onUnsupported} />
        </MantineProvider>,
      );
      await settle();
      expect(onUnsupported).toHaveBeenCalled();
      expect(onChange).not.toHaveBeenCalled();
      expect(screen.getByTestId("value").textContent).toBe(markdown);
    });
  }
});

describe("MarkdownRichEditor emits what the user edits", () => {
  test("typing reports the Markdown of the text, a keystroke at a time", async () => {
    const onChange = vi.fn();
    const { box } = await mount({ value: "hello", onChange });
    await typeAtEnd(box, " world");
    expect(onChange).toHaveBeenLastCalledWith("hello world");
    expect(screen.getByTestId("value")).toHaveTextContent("hello world");
  });

  test("a space that ends the text, a paragraph or a list item is not written as an entity", async () => {
    for (const value of ["hello", "para one\n\npara two", "- item\n- two"]) {
      const onChange = vi.fn();
      const { box, unmount } = await mount({ value, onChange });
      await typeAt(box, "! ", { run: "first" });
      const written = String(onChange.mock.lastCall?.[0]);
      expect(written).toContain("!");
      expect(onChange.mock.calls.flat().join("")).not.toContain("&#x20;");
      unmount();
    }
  });

  test("a typed `&#x20;` is escaped text, never mistaken for the editor's own trailing entity", async () => {
    const onChange = vi.fn();
    const { box } = await mount({ value: "x", onChange });
    await typeAtEnd(box, "&#x20;");
    expect(onChange).toHaveBeenLastCalledWith("x\\&#x20;");
    expect(screen.getByTestId("value").textContent).toBe("x\\&#x20;");
  });

  test("a hard line break survives an edit, in either spelling, and a soft one stays soft", async () => {
    for (const [value, written] of [
      ["a  \nb", "a\\\nb!"],
      ["a\\\nb", "a\\\nb!"],
      ["first line  \nsecond line\n\nlast", "first line\\\nsecond line\n\nlast!"],
      ["a\nb", "a\nb!"],
    ]) {
      const onChange = vi.fn();
      const { box, unmount } = await mount({ value, onChange });
      await typeAtEnd(box, "!");
      expect(onChange).toHaveBeenLastCalledWith(written);
      unmount();
    }
  });

  test("Shift+Enter makes a hard break that is written as one; at the end of the text it writes nothing", async () => {
    const onChange = vi.fn();
    const { box } = await mount({ value: "ab", onChange });
    await typeAt(box, "{Shift>}{Enter}{/Shift}", { offset: 1 });
    expect(onChange).toHaveBeenLastCalledWith("a\\\nb");
    const atEnd = vi.fn();
    const { box: other } = await mount({ value: "hello", onChange: atEnd });
    await typeAtEnd(other, "{Shift>}{Enter}{/Shift}");
    expect(atEnd).not.toHaveBeenCalled();
    expect(screen.getAllByTestId("value").at(-1)?.textContent).toBe("hello");
  });

  test("the first edit to a list written with * saves the whole text in the editor's own style", async () => {
    const onChange = vi.fn();
    const { box } = await mount({ value: "* a\n* b", onChange });
    await typeAtEnd(box, "!");
    expect(onChange).toHaveBeenLastCalledWith("- a\n- b!");
  });

  test("a value from outside replaces the text and is not echoed back as an edit", async () => {
    const onChange = vi.fn();
    const view = render(
      <MantineProvider env="test">
        <MarkdownRichEditor value="one" onChange={onChange} onUnsupported={() => {}} labelId="label" required={false} invalid={false} minRows={4} />
        <div id="label">Context</div>
      </MantineProvider>,
    );
    const first = await screen.findByRole("textbox", { name: "Context" });
    expect(first).toHaveTextContent("one");
    await typeAtEnd(first, "!");
    expect(onChange).toHaveBeenLastCalledWith("one!");
    onChange.mockClear();
    view.rerender(
      <MantineProvider env="test">
        <MarkdownRichEditor value={"two\n\n* x"} onChange={onChange} onUnsupported={() => {}} labelId="label" required={false} invalid={false} minRows={4} />
        <div id="label">Context</div>
      </MantineProvider>,
    );
    await settle();
    const second = screen.getByRole("textbox", { name: "Context" });
    expect(second).toHaveTextContent("two");
    expect(second).toHaveTextContent("x");
    expect(onChange).not.toHaveBeenCalled();
    await typeAtEnd(second, "?");
    expect(onChange).toHaveBeenLastCalledWith("two\n\n- x?");
  });

  test("a fenced code block is edited in a plain textarea that keeps its fence and language", async () => {
    const onChange = vi.fn();
    const { box } = await mount({ value: "```ts\nconst a = 1;\n```", onChange });
    const code = within(box).getByRole("textbox", { name: "Code block" });
    expect(code).toHaveValue("const a = 1;");
    const user = userEvent.setup();
    await user.click(code);
    await user.type(code, "{Enter}run();");
    expect(onChange).toHaveBeenCalled();
    expect(onChange.mock.lastCall?.[0]).toBe("```ts\nconst a = 1;\nrun();\n```");
  });
});

describe("MarkdownRichEditor toolbar", () => {
  test("inserting a code block puts the caret in its textarea and the text reads back as a fence", async () => {
    const onChange = vi.fn();
    const { box } = await mount({ value: "intro", onChange });
    await typeAtEnd(box, "!");
    await userEvent.setup().click(screen.getByRole("button", { name: "Insert code block" }));
    const code = await within(box).findByRole("textbox", { name: "Code block" });
    expect(code).toHaveFocus();
    await userEvent.setup().keyboard("run();");
    expect(onChange).toHaveBeenLastCalledWith("intro!\n\n```\nrun();\n```");
  });
});

describe("MarkdownRichEditor is a named, multi-line textbox in the field's state", () => {
  test("named by the label, multi-line, required and invalid as the field is, described by the field's texts", async () => {
    const { box, rerender } = await mount({ value: "x", required: true, invalid: true, describedBy: "hint err" });
    expect(box).toHaveAttribute("role", "textbox");
    expect(box).toHaveAttribute("aria-multiline", "true");
    expect(box).toHaveAttribute("aria-required", "true");
    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(box).toHaveAttribute("aria-describedby", "hint err");
    rerender(
      <MantineProvider env="test">
        <Field value="x" />
      </MantineProvider>,
    );
    await settle();
    // A re-render with a new `Field` tree remounts it: the state attributes follow the props again.
    const plain = screen.getByRole("textbox", { name: "Context" });
    expect(plain).not.toHaveAttribute("aria-required");
    expect(plain).not.toHaveAttribute("aria-invalid");
    expect(plain).not.toHaveAttribute("aria-describedby");
  });

  test("the editor follows the colour scheme and the language", async () => {
    const light = await mount({ value: "x" });
    expect(document.querySelector(".dark-theme")).toBeNull();
    expect(screen.getByRole("radio", { name: "Bold" })).toBeInTheDocument();
    light.unmount();
    await mount({ value: "x" }, "dark");
    expect(document.querySelector(".dark-theme")).not.toBeNull();
    await act(() => i18n.changeLanguage("pl"));
    expect(await screen.findByRole("radio", { name: "Pogrubienie" })).toBeInTheDocument();
  });
});
