import type { ComponentProps } from "react";
import type MarkdownRichEditor from "../components/MarkdownRichEditor";

/**
 * A stand-in for the visual Markdown editor in the tests of the pages and components that merely USE a `MarkdownField`: a plain
 * textarea exposed as the same named, multi-line, required/invalid `textbox`, so those tests type into it and read its value without
 * loading Lexical. Install it with
 * `vi.mock("../components/MarkdownRichEditor", () => import("../test/markdownRichEditorStub"))`; `MarkdownRichEditor.test.tsx` runs
 * the real editor.
 */
export default function MarkdownRichEditorStub({
  value,
  onChange,
  labelId,
  describedBy,
  required,
  invalid,
}: ComponentProps<typeof MarkdownRichEditor>) {
  return (
    <textarea
      role="textbox"
      aria-labelledby={labelId}
      aria-describedby={describedBy}
      aria-multiline="true"
      aria-required={required || undefined}
      aria-invalid={invalid || undefined}
      value={value}
      onChange={(event) => onChange(event.currentTarget.value)}
    />
  );
}
