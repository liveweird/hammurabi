import { afterEach, describe, expect, test, vi } from "vitest";
import { notifications } from "@mantine/notifications";
import i18n from "../i18n";
import { showSuccessToast } from "./toast";

describe("showSuccessToast", () => {
  afterEach(async () => {
    vi.restoreAllMocks();
    await i18n.changeLanguage("en");
  });

  test("shows a teal toast whose close button has an accessible name in the viewer's language", async () => {
    const show = vi.spyOn(notifications, "show").mockReturnValue("id");
    showSuccessToast("Saved");
    expect(show).toHaveBeenLastCalledWith(expect.objectContaining({ message: "Saved", color: "teal", closeButtonProps: { "aria-label": "Close" } }));
    await i18n.changeLanguage("pl");
    showSuccessToast("Zapisano");
    expect(show).toHaveBeenLastCalledWith(expect.objectContaining({ closeButtonProps: { "aria-label": "Zamknij" } }));
  });
});
