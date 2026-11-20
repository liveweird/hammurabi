import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import { ADR_STATUS_COLOR, ADR_STATUSES } from "../utils/adrStatus";
import AdrStatusBadge from "./AdrStatusBadge";

describe("AdrStatusBadge", () => {
  test("every status renders its localized name in its mapped colour", () => {
    for (const status of ADR_STATUSES) {
      const { unmount } = renderWithProviders(<AdrStatusBadge status={status} />);
      const badge = screen.getByText(/./).closest(".mantine-Badge-root") as HTMLElement;
      expect(badge.getAttribute("style")).toContain(`--mantine-color-${ADR_STATUS_COLOR[status]}-light`);
      unmount();
    }
  });

  test("the label is the status name, not the enum value", () => {
    renderWithProviders(<AdrStatusBadge status="IN_REVIEW" />);
    expect(screen.getByText("In review")).toBeInTheDocument();
  });
});
