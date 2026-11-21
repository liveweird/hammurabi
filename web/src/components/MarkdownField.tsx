import { lazy, Suspense, useId, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Input, SegmentedControl, Text, Textarea } from "@mantine/core";
import { isOneOf, useStoredState } from "../hooks/useStoredState";
import { charCountDescription } from "../utils/charCount";
import LoadingBlock from "./LoadingBlock";

// The visual editor (MDXEditor + Lexical) is a lazy chunk of its own: no page pays for it until a Markdown field mounts.
const MarkdownRichEditor = lazy(() => import("./MarkdownRichEditor"));

type Mode = "visual" | "source";
const MODES: readonly Mode[] = ["visual", "source"];

/** Height of the loading placeholder: the toolbar plus the field's lines (the editor's own minimum). */
const TOOLBAR_REM = 2.5;
const LINE_REM = 1.5;

/**
 * A Markdown text field: a visual (WYSIWYG) editor by default, with a toggle to the Markdown source in a plain textarea. The value is
 * ALWAYS the Markdown string — the two views edit the same text, and the saved text is rendered by `MarkdownView` (what the page shows).
 * The chosen view is remembered across fields and visits. A text the visual editor cannot represent opens as source, with a note, and
 * is never rewritten. The character counter shows near the limit and never cuts the text: `error` is the form's field error, which
 * is where a too-long text is reported. The editable region is named by the label either way.
 */
export default function MarkdownField({
  label,
  value,
  onChange,
  error,
  maxLength,
  required = false,
  description,
  minRows = 4,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: ReactNode;
  maxLength: number;
  required?: boolean;
  description?: string;
  minRows?: number;
}) {
  const { t } = useTranslation();
  const [preferred, setPreferred] = useStoredState<Mode>("markdownField.mode", "visual", isOneOf(MODES));
  // Set when the visual editor refused the text; the preference itself is left alone.
  const [unsupported, setUnsupported] = useState(false);
  // The text this field last handed to the form: a value that differs is a replacement from outside (a posted comment clears its
  // composer), which ends the fallback to the source view: the new text gets the visual editor again.
  const [known, setKnown] = useState(value);
  if (value !== known) {
    setKnown(value);
    setUnsupported(false);
  }
  const edit = (next: string) => {
    setKnown(next);
    onChange(next);
  };
  const mode: Mode = unsupported ? "source" : preferred;
  const id = useId();
  const labelId = `${id}-label`;
  const descriptionId = `${id}-description`;
  const errorId = `${id}-error`;
  const counterId = `${id}-counter`;
  const counter = charCountDescription(value.length, maxLength);
  const describedBy =
    [description ? descriptionId : null, error ? errorId : null, counter ? counterId : null].filter(Boolean).join(" ") || undefined;

  return (
    <Input.Wrapper
      label={label}
      labelElement="div"
      labelProps={{ id: labelId }}
      description={description}
      descriptionProps={{ id: descriptionId }}
      error={error}
      errorProps={{ id: errorId }}
      required={required}
    >
      <SegmentedControl
        size="xs"
        mb={6}
        value={mode}
        onChange={(next) => {
          setUnsupported(false);
          setPreferred(next as Mode);
        }}
        aria-label={t("adrs.markdown.modeAria", { field: label })}
        data={[
          { value: "visual", label: t("adrs.markdown.visual") },
          { value: "source", label: t("adrs.markdown.source") },
        ]}
      />
      {unsupported && (
        <Text size="xs" c="dimmed" mb={6} role="note">
          {t("adrs.markdown.unsupported")}
        </Text>
      )}
      {mode === "visual" ? (
        <Suspense fallback={<LoadingBlock mih={(TOOLBAR_REM + minRows * LINE_REM) * 16} />}>
          <MarkdownRichEditor
            value={value}
            onChange={edit}
            onUnsupported={() => setUnsupported(true)}
            labelId={labelId}
            describedBy={describedBy}
            required={required}
            invalid={Boolean(error)}
            minRows={minRows}
          />
        </Suspense>
      ) : (
        <Textarea
          aria-labelledby={labelId}
          aria-describedby={describedBy}
          autosize
          minRows={minRows}
          maxRows={24}
          value={value}
          onChange={(e) => edit(e.currentTarget.value)}
          error={Boolean(error)}
          aria-required={required || undefined}
        />
      )}
      {counter && <div id={counterId}>{counter}</div>}
    </Input.Wrapper>
  );
}
