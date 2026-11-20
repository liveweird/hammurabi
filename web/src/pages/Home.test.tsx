import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import { headingOutline } from "../test/headings";
import Home from "./Home";

describe("Home", () => {
  test("is a placeholder: the Hammurabi title and a hint that ADRs are coming", () => {
    renderWithProviders(<Home />);
    expect(screen.getByRole("heading", { level: 2, name: "Hammurabi" })).toBeInTheDocument();
    expect(screen.getByText("Welcome to Hammurabi")).toBeInTheDocument();
    expect(screen.getByText(/Architecture Decision Records \(ADRs\) are coming/)).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Hammurabi"]]);
  });
});
