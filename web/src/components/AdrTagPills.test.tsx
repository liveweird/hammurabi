import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import AdrTagPills from "./AdrTagPills";

describe("AdrTagPills", () => {
  test("shows a pill per tag and marks a retired one for assistive technology, not only by style", () => {
    renderWithProviders(<AdrTagPills tags={[{ name: "data", retired: false }, { name: "legacy", retired: true }]} />);
    expect(screen.getByText("data")).toBeInTheDocument();
    expect(screen.getByText("legacy (retired)")).toBeInTheDocument();
  });

  test("no tags reads as the given words, or as nothing in a table cell", () => {
    const { unmount } = renderWithProviders(<AdrTagPills tags={[]} none="No tags" />);
    expect(screen.getByText("No tags")).toBeInTheDocument();
    unmount();
    const bare = renderWithProviders(<AdrTagPills tags={[]} />);
    expect(bare.container.querySelector(".mantine-Badge-root, .mantine-Text-root")).toBeNull();
  });
});
