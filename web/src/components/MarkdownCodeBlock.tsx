import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { useCodeBlockEditorContext, type CodeBlockEditorProps } from "@mdxeditor/editor";
import classes from "./MarkdownRichEditor.module.css";

/**
 * The fenced code block editor (the catch-all descriptor in `MarkdownRichEditor`, whatever the fence's language): a plain monospace
 * textarea (MDXEditor's own is CodeMirror, a heavy chunk for what a decision record needs). The fence's language and meta stay on the
 * node untouched; only the code is edited. Uncontrolled: the textarea owns the text and `setCode` mirrors it into the document, so a
 * cursor never jumps on the round trip. Lexical knows a textarea inside a decorator and leaves its keys and input alone, so no event
 * is stopped here (stopping one would also hide it from React).
 */
export default function MarkdownCodeBlock({ code, focusEmitter }: CodeBlockEditorProps) {
  const { t } = useTranslation();
  const { setCode } = useCodeBlockEditorContext();
  const ref = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    focusEmitter.subscribe(() => ref.current?.focus());
    // The emitter keeps ONE subscription and has no unsubscribe: a no-op replaces ours when the block goes.
    return () => focusEmitter.subscribe(() => {});
  }, [focusEmitter]);

  return (
    <textarea
      ref={ref}
      className={classes.codeBlock}
      aria-label={t("adrs.markdown.codeBlockAria")}
      defaultValue={code}
      rows={Math.max(3, code.split("\n").length)}
      spellCheck={false}
      onChange={(event) => setCode(event.currentTarget.value)}
    />
  );
}
