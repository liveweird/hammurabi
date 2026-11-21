import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { ApiError } from "../api/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import AdrExportButton from "./AdrExportButton";

const saveFile = vi.fn();
vi.mock("../utils/saveFile", () => ({ saveFile: (download: unknown) => saveFile(download) }));

const FILE = { blob: new Blob(["x"]), fileName: "ADR-0001-x.md" };

describe("AdrExportButton", () => {
  afterEach(() => saveFile.mockReset());

  test("runs the export and hands the file to the browser", async () => {
    const run = vi.fn().mockResolvedValue(FILE);
    renderWithProviders(<AdrExportButton label="Export Markdown" run={run} />);
    await userEvent.click(screen.getByRole("button", { name: "Export Markdown" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledWith(FILE));
    expect(run).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test.each([
    [404, "This ADR no longer exists."],
    [400, "Cannot export: an export holds at most 500 ADRs, or a filter was refused. Narrow the filters and try again."],
    [429, "Too many requests. Wait a minute and try again."],
    [500, "Load failed (500)"],
  ])("a %s failure says so inline and saves nothing", async (status, words) => {
    renderWithProviders(<AdrExportButton label="Export (ZIP)" run={() => Promise.reject(new ApiError(status, null))} />);
    await userEvent.click(screen.getByRole("button", { name: "Export (ZIP)" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(words);
    expect(saveFile).not.toHaveBeenCalled();
  });

  test("a 429 export-busy asks to try again in a moment", async () => {
    const busy = new ApiError(429, { type: "urn:hammurabi:problem:export-busy", title: "x", status: 429 });
    renderWithProviders(<AdrExportButton label="Export (ZIP)" run={() => Promise.reject(busy)} />);
    await userEvent.click(screen.getByRole("button", { name: "Export (ZIP)" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Other exports are running right now. Try again in a moment.");
  });

  test("a network failure reads as a network failure", async () => {
    renderWithProviders(<AdrExportButton label="Export (ZIP)" run={() => Promise.reject(new TypeError("Failed to fetch"))} />);
    await userEvent.click(screen.getByRole("button", { name: "Export (ZIP)" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Network error. Check your connection and try again.");
  });

  test("can be disabled", () => {
    renderWithProviders(<AdrExportButton label="Export (ZIP)" run={vi.fn()} disabled />);
    expect(screen.getByRole("button", { name: "Export (ZIP)" })).toBeDisabled();
  });
});
