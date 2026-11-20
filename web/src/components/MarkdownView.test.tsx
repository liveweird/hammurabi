import { afterEach, describe, expect, test } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import MarkdownView, { type MarkdownVariant } from "./MarkdownView";
import classes from "./MarkdownView.module.css";

function renderView(markdown: string, variant?: MarkdownVariant) {
  return render(
    <MantineProvider env="test">
      <MarkdownView variant={variant}>{markdown}</MarkdownView>
    </MantineProvider>,
  );
}

afterEach(cleanup);

describe("MarkdownView (document, the default)", () => {
  test("renders :shortcode: as the native emoji", () => {
    renderView("Great job :tada: keep it up :+1:");
    expect(screen.getByText("Great job 🎉 keep it up 👍")).toBeInTheDocument();
  });

  test("leaves unknown shortcodes literal", () => {
    renderView("stays :notarealshortcode: literal");
    expect(screen.getByText("stays :notarealshortcode: literal")).toBeInTheDocument();
  });

  test("GFM still renders alongside the emoji plugin", () => {
    renderView("| a | b |\n| - | - |\n| 1 | :tada: |");
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByText("🎉")).toBeInTheDocument();
  });

  test("keeps real headings and plain links, and carries no message class", () => {
    renderView("# Title\n\n[docs](https://example.com/docs)");
    expect(screen.getByRole("heading", { level: 1, name: "Title" })).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "docs" });
    expect(link).not.toHaveAttribute("target");
    expect(link.closest(`.${classes.message}`)).toBeNull();
  });

  test("renders no raw HTML", () => {
    renderView("<b>bold</b> and <script>alert(1)</script>");
    expect(document.querySelector("b")).toBeNull();
    expect(document.querySelector("script")).toBeNull();
  });
});

describe("MarkdownView (message)", () => {
  test("renders headings as bold paragraphs, never as heading elements", () => {
    renderView("# One\n\n### Three\n\n###### Six", "message");
    expect(screen.queryAllByRole("heading")).toHaveLength(0);
    for (const text of ["One", "Three", "Six"]) {
      const node = screen.getByText(text);
      expect(node.tagName).toBe("P");
      expect(node).toHaveClass(classes.heading);
    }
  });

  test("opens links in a new tab without opener or referrer", () => {
    renderView("[docs](https://example.com/docs)", "message");
    const link = screen.getByRole("link", { name: "docs" });
    expect(link).toHaveAttribute("href", "https://example.com/docs");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  test("renders an image as a link labelled by its alt text (the CSP blocks remote images)", () => {
    renderView("![a cat](https://example.com/cat.png)", "message");
    expect(document.querySelector("img")).toBeNull();
    const link = screen.getByRole("link", { name: "a cat" });
    expect(link).toHaveAttribute("href", "https://example.com/cat.png");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  test("an image without alt text is labelled by its address", () => {
    renderView("![](https://example.com/cat.png)", "message");
    expect(screen.getByRole("link", { name: "https://example.com/cat.png" })).toBeInTheDocument();
  });

  test("an image with an unsafe address stays as its alt text, not a link", () => {
    renderView("![nope](javascript:alert(1))", "message");
    expect(document.querySelector("img")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByText("nope")).toBeInTheDocument();
  });

  test("renders no raw HTML", () => {
    renderView("<img src=x onerror=alert(1)> <b>bold</b> <script>alert(1)</script>", "message");
    expect(document.querySelector("img")).toBeNull();
    expect(document.querySelector("b")).toBeNull();
    expect(document.querySelector("script")).toBeNull();
  });

  test("emoji shortcodes and GFM still render, inside the message class", () => {
    renderView("done :tada:\n\n| a |\n| - |\n| 1 |", "message");
    expect(screen.getByText("done 🎉")).toBeInTheDocument();
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByText("done 🎉").parentElement).toHaveClass(classes.message);
  });
});
