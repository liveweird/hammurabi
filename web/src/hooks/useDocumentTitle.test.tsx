import { afterEach, describe, expect, test } from "vitest";
import { useDocumentTitle } from "./useDocumentTitle";
import i18n from "../i18n";
import { renderWithProviders } from "../test/render";

function Page({ title }: { title: string }) {
  useDocumentTitle(title);
  return null;
}

describe("useDocumentTitle", () => {
  afterEach(() => {
    document.title = "";
  });

  test("titles the tab '{page} · Hammurabi' while the page is mounted and follows a change", () => {
    const { rerender, unmount } = renderWithProviders(<Page title="Users" />);
    expect(document.title).toBe("Users · Hammurabi");
    rerender(<Page title="Teams" />);
    expect(document.title).toBe("Teams · Hammurabi");
    unmount();
    expect(document.title).toBe("Hammurabi");
  });

  test("an empty title leaves the bare brand", () => {
    renderWithProviders(<Page title="" />);
    expect(document.title).toBe("Hammurabi");
  });

  test("a page titled with the brand itself is not doubled, in either language", async () => {
    renderWithProviders(<Page title="Hammurabi" />);
    expect(document.title).toBe("Hammurabi");
    await i18n.changeLanguage("pl");
    try {
      renderWithProviders(<Page title="Użytkownicy" />);
      expect(document.title).toBe("Użytkownicy · Hammurabi");
    } finally {
      await i18n.changeLanguage("en");
    }
  });
});
