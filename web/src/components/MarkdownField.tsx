import { useId, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Input, Paper, SegmentedControl, Text, Textarea } from "@mantine/core";
import { charCountDescription } from "../utils/charCount";
import MarkdownView from "./MarkdownView";

type Mode = "write" | "preview";

/**
 * A Markdown text field with a Write/Preview toggle (a plain Textarea; the rich editor would need a new dependency). Preview renders
 * through the same `MarkdownView` ("message" variant) the page shows the saved text with, so what you see is what is stored. The
 * character counter appears near the limit; `error` is the form's field error.
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
  const [mode, setMode] = useState<Mode>("write");
  const labelId = useId();
  const counter = charCountDescription(value.length, maxLength);

  return (
    <Input.Wrapper label={label} labelElement="div" labelProps={{ id: labelId }} description={description} error={error} required={required}>
      <SegmentedControl
        size="xs"
        mb={6}
        value={mode}
        onChange={(next) => setMode(next as Mode)}
        aria-label={t("adrs.markdown.modeAria", { field: label })}
        data={[
          { value: "write", label: t("adrs.markdown.write") },
          { value: "preview", label: t("adrs.markdown.preview") },
        ]}
      />
      {mode === "write" ? (
        <Textarea
          aria-labelledby={labelId}
          autosize
          minRows={minRows}
          maxRows={24}
          value={value}
          onChange={(e) => onChange(e.currentTarget.value)}
          error={Boolean(error)}
          description={counter}
          inputWrapperOrder={["input", "description"]}
        />
      ) : (
        <Paper withBorder p="sm" mih={minRows * 24}>
          {value.trim() === "" ? (
            <Text c="dimmed" size="sm">
              {t("adrs.markdown.nothingToPreview")}
            </Text>
          ) : (
            <MarkdownView variant="message">{value}</MarkdownView>
          )}
        </Paper>
      )}
    </Input.Wrapper>
  );
}
