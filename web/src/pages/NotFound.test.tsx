import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import NotFound from "./NotFound";

describe("NotFound", () => {
  test("renders the message and the My work link", () => {
    renderWithProviders(<NotFound />);
    expect(screen.getByRole("heading", { level: 2, name: "Page not found" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to My work" })).toHaveAttribute("href", "/my-work");
  });
});
