import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import AdrMatchText from "./AdrMatchText";

describe("AdrMatchText", () => {
  test("wraps the hits in marks and leaves the rest as plain text", () => {
    const { container } = renderWithProviders(
      <p>
        <AdrMatchText segments={[{ text: "Use ", hit: false }, { text: "Postgres", hit: true }, { text: " everywhere", hit: false }]} />
      </p>,
    );
    expect(container.querySelector("p")).toHaveTextContent("Use Postgres everywhere");
    const marks = container.querySelectorAll("mark");
    expect(marks).toHaveLength(1);
    expect(marks[0]).toHaveTextContent("Postgres");
  });

  test("segment text is rendered as text, never as markup", () => {
    const { container } = renderWithProviders(
      <p>
        <AdrMatchText segments={[{ text: '<img src=x onerror="alert(1)">', hit: false }, { text: "<b>bold</b>", hit: true }]} />
      </p>,
    );
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("b")).toBeNull();
    expect(screen.getByText('<img src=x onerror="alert(1)">', { exact: false })).toBeInTheDocument();
    expect(container.querySelector("mark")).toHaveTextContent("<b>bold</b>");
  });
});
