import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture } from "../test/adrFixtures";
import { noContent, problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import AdrDetailsSection from "./AdrDetailsSection";

describe("AdrDetailsSection", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows the key, the dates (UTC, deterministic) and the expiry date", () => {
    renderWithProviders(<AdrDetailsSection adr={adrFixture({ expiresOn: "2027-06-30" })} />);
    expect(screen.getByText("ADR-0001")).toBeInTheDocument();
    // Raised and last moved at the same minute in the fixture.
    expect(screen.getAllByText("2026-11-01 09:30")).toHaveLength(2);
    expect(screen.getByText("2026-11-02 10:15")).toBeInTheDocument();
    expect(screen.getByText("2027-06-30")).toBeInTheDocument();
  });

  test("no expiry date says so; the editor button follows canManageParticipants", () => {
    const { unmount } = renderWithProviders(<AdrDetailsSection adr={adrFixture()} />);
    expect(screen.getByText("No expiry date")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit the expiry date" })).toBeInTheDocument();
    unmount();
    renderWithProviders(<AdrDetailsSection adr={adrFixture({ canManageParticipants: false })} />);
    expect(screen.queryByRole("button", { name: "Edit the expiry date" })).not.toBeInTheDocument();
  });

  test("sets the date by sending the document unchanged with only the date replaced", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? noContent() : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrDetailsSection adr={adrFixture({ revision: 8, status: "ACTIVE", context: "C" })} />);
    await user.click(screen.getByRole("button", { name: "Edit the expiry date" }));
    await user.type(screen.getByLabelText("Expiry date"), "2030-01-31");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByLabelText("Expiry date")).not.toBeInTheDocument());
    const put = stub.find("PUT", "/api/v1/adrs/1");
    expect(put?.headers.get("X-Expected-Revision")).toBe("8");
    expect(put?.body).toMatchObject({ name: "Use PostgreSQL", problem: "We need a database.", context: "C", expiresOn: "2030-01-31" });
  });

  test("an emptied field clears the date", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? noContent() : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrDetailsSection adr={adrFixture({ expiresOn: "2027-06-30" })} />);
    await user.click(screen.getByRole("button", { name: "Edit the expiry date" }));
    await user.clear(screen.getByLabelText("Expiry date"));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1")?.body).toMatchObject({ expiresOn: null }));
  });

  test("a date in the past is explained, a stale revision offers the reload, and Cancel leaves the editor", async () => {
    let answer = problem(400);
    stubFetch((r) => (r.method === "PUT" ? answer : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrDetailsSection adr={adrFixture()} />);
    await user.click(screen.getByRole("button", { name: "Edit the expiry date" }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("expiry date must not be in the past");
    answer = problem(409, "urn:hammurabi:problem:revision-conflict");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await user.click(await screen.findByRole("button", { name: "Reload the ADR" }));
    await waitFor(() => expect(screen.queryByLabelText("Expiry date")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "Edit the expiry date" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByText("No expiry date")).toBeInTheDocument();
  });
});
