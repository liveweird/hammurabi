import { describe, expect, test } from "vitest";
import { adrFixture } from "../test/adrFixtures";
import { renderWithProviders, screen } from "../test/render";
import { usePinnedBrowserTimeZone } from "../test/timeZone";
import AdrDetailsSection from "./AdrDetailsSection";

describe("AdrDetailsSection", () => {
  usePinnedBrowserTimeZone("America/Los_Angeles");
  test("shows the key and dates in the browser time zone", () => {
    renderWithProviders(<AdrDetailsSection adr={adrFixture()} />);
    expect(screen.getByText("ADR-0001")).toBeInTheDocument();
    // Raised and last moved at the same minute in the fixture.
    expect(screen.getAllByText("2026-11-01 01:30")).toHaveLength(2);
    expect(screen.getByText("2026-11-02 02:15")).toBeInTheDocument();
  });
});
