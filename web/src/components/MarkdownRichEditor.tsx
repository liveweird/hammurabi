import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { useTranslation } from "react-i18next";
import type { ParseKeys } from "i18next";
import { useComputedColorScheme } from "@mantine/core";
import {
  BlockTypeSelect,
  BoldItalicUnderlineToggles,
  CodeToggle,
  CreateLink,
  InsertCodeBlock,
  InsertTable,
  ListsToggle,
  MDXEditor,
  Separator,
  StrikeThroughSupSubToggles,
  UndoRedo,
  addExportVisitor$,
  codeBlockPlugin,
  headingsPlugin,
  linkDialogPlugin,
  linkPlugin,
  listsPlugin,
  markdownShortcutPlugin,
  quotePlugin,
  realmPlugin,
  tablePlugin,
  thematicBreakPlugin,
  toolbarPlugin,
  type LexicalVisitor,
  type ToMarkdownOptions,
  type Translation,
} from "@mdxeditor/editor";
import "@mdxeditor/editor/style.css";
import MarkdownCodeBlock from "./MarkdownCodeBlock";
import classes from "./MarkdownRichEditor.module.css";

// What an edit writes back: hyphen bullets and rules (the form the Markdown export and the saved text already use).
const TO_MARKDOWN: ToMarkdownOptions = { bullet: "-", rule: "-", listItemIndent: "one" };

// What the user did, as opposed to what the editor does on its own while mounting (it re-writes the text it loads and turns bare
// URLs into links a moment later). Capture phase: the code block's textarea stops these events at the target.
const USER_EVENTS = ["keydown", "beforeinput", "paste", "cut", "drop", "click"] as const;

// mdast-util-to-markdown writes a space that ends a line (the text, a paragraph, a list item) as `&#x20;` until the next character
// arrives. The form does not trim a Markdown field, so the entity is dropped here: one at a time from each line end, never an ESCAPED
// one (`\&#x20;` is the literal text the user typed), and never inside a fenced code block (code is written verbatim).
const SPACE_ENTITY = "&#x20;";
const FENCE = /^\s*(`{3,}|~{3,})/;

function withoutSpaceEntities(line: string): string {
  let text = line;
  while (text.endsWith(SPACE_ENTITY)) {
    const before = text.slice(0, -SPACE_ENTITY.length);
    let backslashes = 0;
    while (before[before.length - 1 - backslashes] === "\\") backslashes += 1;
    if (backslashes % 2 === 1) break;
    text = before;
  }
  return text;
}

function withoutTrailingSpaceEntities(markdown: string): string {
  let fence: string | null = null;
  return markdown
    .split("\n")
    .map((line) => {
      const marker = FENCE.exec(line)?.[1] ?? null;
      if (fence === null && marker !== null) fence = marker;
      else if (fence !== null && marker?.[0] === fence[0] && marker.length >= fence.length) fence = null;
      else if (fence === null) return withoutSpaceEntities(line);
      return line;
    })
    .join("\n");
}

// MDXEditor exports a line break as the text "\n", which Markdown reads as a SOFT break (a space): "a  \nb" would come back as "a\nb" and
// render "a b" while the editor still shows two lines. This visitor outranks it and writes a real `break` node (a hard break, a backslash and a newline).
const hardBreakPlugin = realmPlugin({
  init(realm) {
    const visitor: LexicalVisitor = {
      priority: 10,
      testLexicalNode: (node): node is typeof node => node.getType() === "linebreak",
      visitLexicalNode: ({ lexicalNode, mdastParent, actions }) => {
        // A break nothing follows (Shift+Enter at the end of a paragraph, not yet typed past) is not written: it would be a stray `\`.
        if (lexicalNode.getNextSibling() === null) return;
        actions.appendToParent(mdastParent, { type: "break" });
      },
    };
    realm.pubIn({ [addExportVisitor$]: visitor });
  },
});

function createPlugins() {
  return [
    headingsPlugin(),
    listsPlugin(),
    quotePlugin(),
    linkPlugin(),
    linkDialogPlugin(),
    thematicBreakPlugin(),
    tablePlugin(),
    codeBlockPlugin({ codeBlockEditorDescriptors: [{ priority: 0, match: () => true, Editor: MarkdownCodeBlock }] }),
    markdownShortcutPlugin(),
    hardBreakPlugin(),
    toolbarPlugin({
      toolbarContents: () => (
        <>
          <UndoRedo />
          <Separator />
          {/* Underline and sub/superscript are raw HTML in Markdown, which the app never renders: not offered. */}
          <BoldItalicUnderlineToggles options={["Bold", "Italic"]} />
          <StrikeThroughSupSubToggles options={["Strikethrough"]} />
          <CodeToggle />
          <Separator />
          <ListsToggle />
          <BlockTypeSelect />
          <Separator />
          <CreateLink />
          <InsertTable />
          <InsertCodeBlock />
        </>
      ),
    }),
  ];
}

/**
 * The visual editor of a Markdown field (Lettuce's MDXEditor wrapper, ported; lazy-loaded by `MarkdownField`, so Lexical stays out of
 * the main bundle). The document model IS the Markdown string: `value` in, `onChange(markdown)` out. Three rules keep the stored text
 * honest:
 *
 * - **Only a user's edit is emitted.** MDXEditor re-serialises what it loads (bullet style, escaping, link syntax); an unchanged field
 *   must save the original text, or the server's per-field history would record an edit nobody made. So `onChange` is dropped while
 *   the editor reports its own initial normalisation, until the user has touched the field, and when it equals what the parent holds.
 * - **A new value from outside re-mounts the editor** (a posted comment clears its composer): a fresh mount is a clean slate, with its
 *   own normalisation to ignore.
 * - **Nothing is dropped.** HTML is not processed, so raw HTML, reference links, images and anything else the editor cannot represent
 *   fail to parse, and `onUnsupported` hands the field to the Markdown source view instead.
 *
 * The editable region is named by the field's label (`labelId`), multi-line, required and invalid as the field is.
 */
export default function MarkdownRichEditor({
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
  onChange: (markdown: string) => void;
  onUnsupported: () => void;
  labelId: string;
  describedBy?: string;
  required: boolean;
  invalid: boolean;
  minRows: number;
}) {
  const { t, i18n } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const plugins = useMemo(() => createPlugins(), []);
  const translation = useMemo<Translation>(
    () => (key, defaultValue, interpolations) =>
      t(`markdownEditor.${key}` as ParseKeys, { defaultValue, ...interpolations }) as string,
    [t],
  );
  const containerRef = useRef<HTMLDivElement>(null);
  const touched = useRef(false);
  // The text the parent holds as far as this editor knows: its own last emission, or the value it was last given.
  const [held, setHeld] = useState(value);
  const [mounts, setMounts] = useState(0);
  if (value !== held) {
    setHeld(value);
    setMounts(mounts + 1);
  }
  // The editor's own strings are fixed at mount, so a language switch mounts a fresh one.
  const editorKey = `${i18n.resolvedLanguage}-${mounts}`;

  useEffect(() => {
    const root = containerRef.current;
    if (!root) return;
    touched.current = false;
    const touch = () => {
      touched.current = true;
    };
    for (const name of USER_EVENTS) root.addEventListener(name, touch, true);
    return () => {
      for (const name of USER_EVENTS) root.removeEventListener(name, touch, true);
    };
  }, [editorKey]);

  // Lexical renders the editable region itself, so the field's name and state are set on it from outside; the region appears after the
  // first commit and again after a re-mount, hence the observer.
  useEffect(() => {
    const root = containerRef.current;
    if (!root) return;
    const label = () => {
      const box = root.querySelector<HTMLElement>('[role="textbox"]');
      if (!box) return;
      box.setAttribute("aria-labelledby", labelId);
      box.setAttribute("aria-multiline", "true");
      if (required) box.setAttribute("aria-required", "true");
      else box.removeAttribute("aria-required");
      if (invalid) box.setAttribute("aria-invalid", "true");
      else box.removeAttribute("aria-invalid");
      if (describedBy) box.setAttribute("aria-describedby", describedBy);
      else box.removeAttribute("aria-describedby");
    };
    label();
    const observer = new MutationObserver(label);
    observer.observe(root, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, [labelId, describedBy, required, invalid, editorKey]);

  return (
    <div
      ref={containerRef}
      className={classes.root}
      data-invalid={invalid || undefined}
      style={{ "--markdown-min-rows": minRows } as CSSProperties}
    >
      <MDXEditor
        key={editorKey}
        markdown={value}
        className={`${classes.editor} ${scheme === "dark" ? "dark-theme" : ""}`}
        contentEditableClassName={classes.content}
        plugins={plugins}
        translation={translation}
        toMarkdownOptions={TO_MARKDOWN}
        suppressHtmlProcessing
        onError={onUnsupported}
        onChange={(markdown, initialNormalisation) => {
          const text = withoutTrailingSpaceEntities(markdown);
          if (initialNormalisation || !touched.current || text === held) return;
          setHeld(text);
          onChange(text);
        }}
      />
    </div>
  );
}
