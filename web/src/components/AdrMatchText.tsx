import { Fragment } from "react";
import { Mark } from "@mantine/core";
import type { SnippetSegment } from "../api/adrs";

/**
 * A full-text match as the server sends it: plain-text segments, `hit` the words that matched. The text is rendered as React TEXT (never
 * parsed as markup, so an ADR that contains `<img onerror=...>` shows those characters) and each hit is wrapped in a `<mark>`. Mantine's
 * stock yellow highlight: a hue the brand and the status vocabulary leave alone.
 */
export default function AdrMatchText({ segments }: { segments: readonly SnippetSegment[] }) {
  return (
    <>
      {segments.map((segment, index) =>
        segment.hit ? <Mark key={index}>{segment.text}</Mark> : <Fragment key={index}>{segment.text}</Fragment>,
      )}
    </>
  );
}
