import { describe, expect, test } from "vitest";
import { renderWithProviders, screen, within } from "../test/render";
import { ADR_STATUS_COLOR, ADR_STATUSES } from "../utils/adrStatus";
import DecisionLogCounts from "./DecisionLogCounts";

/** The hues `DecisionLog.module.css` has a `.statusDot[data-hue=…]` rule for (vitest does not process module CSS, so the list is pinned here). */
const DOT_HUES = ["gray", "grape", "cyan", "teal", "orange", "red"];

describe("DecisionLogCounts", () => {
  test("every status links to its filtered list and its dot carries the status hue", () => {
    renderWithProviders(<DecisionLogCounts counts={ADR_STATUSES.map((status, i) => ({ status, count: i }))} />);
    const section = screen.getByRole("region", { name: "ADRs by status" });
    expect(within(section).getAllByRole("link")).toHaveLength(ADR_STATUSES.length);
    const hues = [...section.querySelectorAll("[data-hue]")].map((dot) => dot.getAttribute("data-hue"));
    expect(hues).toEqual(ADR_STATUSES.map((status) => ADR_STATUS_COLOR[status]));
  });

  test("every hue of the status vocabulary has a dot rule in the stylesheet", () => {
    for (const hue of Object.values(ADR_STATUS_COLOR)) expect(DOT_HUES).toContain(hue);
  });
});
