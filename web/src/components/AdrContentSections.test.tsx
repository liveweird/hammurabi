import { describe, expect, test } from "vitest";
import { adrFixture } from "../test/adrFixtures";
import { headingOutline } from "../test/headings";
import { renderWithProviders, screen } from "../test/render";
import AdrContentSections from "./AdrContentSections";

describe("AdrContentSections", () => {
  test("renders the five sections in reading order as h3, with Markdown rendered and the empty ones marked", () => {
    renderWithProviders(<AdrContentSections adr={adrFixture({ problem: "We need **a** database.", decision: "# Use PostgreSQL" })} />);
    expect(headingOutline()).toEqual([
      [3, "Problem / question"],
      [3, "Context"],
      [3, "Decision"],
      [3, "Alternatives considered"],
      [3, "Consequences / side effects"],
    ]);
    expect(screen.getByText("a").tagName).toBe("STRONG");
    // The Markdown heading became a bold paragraph, not a heading.
    expect(screen.getByText("Use PostgreSQL").tagName).toBe("P");
    expect(screen.getAllByText("Nothing written yet.")).toHaveLength(3);
  });

  test("whitespace-only text counts as unwritten", () => {
    renderWithProviders(<AdrContentSections adr={adrFixture({ context: " \n " })} />);
    expect(screen.getAllByText("Nothing written yet.")).toHaveLength(4);
  });
});
