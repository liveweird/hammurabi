import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import { headingOutline } from "../test/headings";
import Home from "./Home";

describe("Home", () => {
  test("welcomes the user and leads into the ADRs: all of them, a new one, and the ones they take part in", () => {
    renderWithProviders(<Home />);
    expect(headingOutline()).toEqual([
      [2, "Hammurabi"],
      [3, "Your decision log"],
    ]);
    expect(screen.getByText(/keeps your Architecture Decision Records/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Browse the ADRs" })).toHaveAttribute("href", "/adrs");
    expect(screen.getByRole("link", { name: "Raise an ADR" })).toHaveAttribute("href", "/adrs/new");
    expect(screen.getByRole("link", { name: "ADRs I take part in" })).toHaveAttribute("href", "/adrs?mine=1");
  });

  test("no longer says the ADRs are coming", () => {
    renderWithProviders(<Home />);
    expect(screen.queryByText(/are coming/)).not.toBeInTheDocument();
  });
});
