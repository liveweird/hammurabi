import { describe, expect, test } from "vitest";
import { IconHistory } from "@tabler/icons-react";
import { renderWithProviders, screen } from "../test/render";
import EmptyState from "./EmptyState";

describe("EmptyState", () => {
  test("draws the icon with its label, and the hint under it when there is one", () => {
    const { container, rerender } = renderWithProviders(<EmptyState icon={IconHistory} label="Nothing here" />);
    expect(screen.getByText("Nothing here")).toBeInTheDocument();
    expect(container.querySelector("svg")).not.toBeNull();
    expect(screen.queryByText("Try another filter")).not.toBeInTheDocument();
    rerender(<EmptyState icon={IconHistory} label="Nothing here" hint="Try another filter" />);
    expect(screen.getByText("Try another filter")).toBeInTheDocument();
  });

  test("the compact body is the same icon and label at a smaller size", () => {
    const full = renderWithProviders(<EmptyState icon={IconHistory} label="Nothing here" />);
    const fullIcon = Number(full.container.querySelector("svg")?.getAttribute("width"));
    full.unmount();
    const compact = renderWithProviders(<EmptyState compact icon={IconHistory} label="Nothing here" />);
    expect(screen.getByText("Nothing here")).toBeInTheDocument();
    expect(Number(compact.container.querySelector("svg")?.getAttribute("width"))).toBeLessThan(fullIcon);
  });
});
