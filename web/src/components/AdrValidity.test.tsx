import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture } from "../test/adrFixtures";
import { noContent, problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { fireEvent, renderWithProviders, screen, waitFor } from "../test/render";
import AdrValidity from "./AdrValidity";

describe("AdrValidity", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("no expiry date says so; the editor button follows canEditExpiry", () => {
    const { unmount } = renderWithProviders(<AdrValidity headingId="validity-heading" adr={adrFixture()} />);
    expect(screen.getByText("No expiry date")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit the expiry date" })).toBeInTheDocument();
    unmount();
    renderWithProviders(<AdrValidity headingId="validity-heading" adr={adrFixture({ canEditExpiry: false })} />);
    expect(screen.queryByRole("button", { name: "Edit the expiry date" })).not.toBeInTheDocument();
  });

  test.each([
    adrFixture({ status: "ACTIVE", expiresOn: null }),
    adrFixture({ status: "ACTIVE", expiresOn: "2031-12-30", canEditExpiry: false }),
    adrFixture({ status: "IN_REVIEW", expiresOn: "2031-12-30" }),
    adrFixture({ status: "EXPIRED", expiresOn: "2031-12-30", canEditExpiry: false }),
  ])("offers extension only for dated active ADRs the caller can edit (%s)", (adr) => {
    renderWithProviders(<AdrValidity headingId="validity-heading" adr={adr} />);
    expect(screen.queryByRole("button", { name: "Extend validity" })).not.toBeInTheDocument();
  });

  test("rejects an empty, unchanged or earlier date and extends under the revision shown when opened", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? noContent() : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    const original = adrFixture({ status: "ACTIVE", revision: 8, expiresOn: "2031-12-30", context: "Original context" });
    const { rerender } = renderWithProviders(<AdrValidity headingId="validity-heading" adr={original} />);
    await user.click(screen.getByRole("button", { name: "Extend validity" }));
    const next = screen.getByLabelText(/^New last day/);
    expect(next).toHaveFocus();
    expect(next).toHaveAttribute("min", "2031-12-31");
    for (const date of ["", "2031-12-30", "2031-12-29"]) {
      fireEvent.change(next, { target: { value: date } });
      await user.click(screen.getByRole("button", { name: "Extend validity" }));
      expect(screen.getByRole("alert")).toHaveTextContent("Choose a date after 2031-12-30.");
    }
    expect(stub.count("PUT", "/api/v1/adrs/1")).toBe(0);

    rerender(<AdrValidity headingId="validity-heading" adr={{ ...original, revision: 9, expiresOn: "2032-01-02", context: "Later context" }} />);
    expect(screen.getByText("2031-12-30")).toBeInTheDocument();
    fireEvent.change(next, { target: { value: "2032-02-03" } });
    await user.click(screen.getByRole("button", { name: "Extend validity" }));
    await waitFor(() => expect(screen.queryByLabelText(/^New last day/)).not.toBeInTheDocument());
    const put = stub.find("PUT", "/api/v1/adrs/1");
    expect(put?.headers.get("X-Expected-Revision")).toBe("8");
    expect(put?.body).toMatchObject({ context: "Original context", expiresOn: "2032-02-03" });
    expect(screen.getByRole("button", { name: "Extend validity" })).toHaveFocus();
  });

  test("if the ADR expires while extending, the failed write offers reload and the latest state is read-only", async () => {
    const stub = stubFetch(() => problem(409, "urn:hammurabi:problem:adr-frozen"));
    const user = userEvent.setup();
    const active = adrFixture({ status: "ACTIVE", expiresOn: "2031-12-30" });
    const { rerender } = renderWithProviders(<AdrValidity headingId="validity-heading" adr={active} />);
    await user.click(screen.getByRole("button", { name: "Extend validity" }));
    fireEvent.change(screen.getByLabelText(/^New last day/), { target: { value: "2032-02-03" } });
    await user.click(screen.getByRole("button", { name: "Extend validity" }));
    const reload = await screen.findByRole("button", { name: "Reload the ADR" });
    rerender(<AdrValidity headingId="validity-heading" adr={{ ...active, status: "EXPIRED", canEditExpiry: false }} />);
    expect(screen.getByRole("button", { name: "Extend validity" })).toBeDisabled();
    await user.click(reload);
    await waitFor(() => expect(screen.queryByLabelText(/^New last day/)).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Extend validity" })).not.toBeInTheDocument();
    expect(stub.count("PUT", "/api/v1/adrs/1")).toBe(1);
  });

  test("sets the date by sending the document unchanged with only the date replaced", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? noContent() : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrValidity headingId="validity-heading" adr={adrFixture({ revision: 8, status: "ACTIVE", context: "C" })} />);
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
    renderWithProviders(<AdrValidity headingId="validity-heading" adr={adrFixture({ expiresOn: "2027-06-30" })} />);
    await user.click(screen.getByRole("button", { name: "Edit the expiry date" }));
    await user.clear(screen.getByLabelText("Expiry date"));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1")?.body).toMatchObject({ expiresOn: null }));
  });

  test("an open expiry draft uses its original document and revision after a refetch", async () => {
    const stub = stubFetch(() => problem(409, "urn:hammurabi:problem:revision-conflict"));
    const user = userEvent.setup();
    const original = adrFixture({ revision: 8, context: "Original context", expiresOn: "2030-01-31" });
    const { rerender } = renderWithProviders(<AdrValidity headingId="validity-heading" adr={original} />);
    await user.click(screen.getByRole("button", { name: "Edit the expiry date" }));
    await user.clear(screen.getByLabelText("Expiry date"));
    rerender(<AdrValidity headingId="validity-heading" adr={{ ...original, revision: 9, context: "New context", expiresOn: "2030-02-01" }} />);
    await user.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByRole("button", { name: "Reload the ADR" });
    const put = stub.find("PUT", "/api/v1/adrs/1");
    expect(put?.headers.get("X-Expected-Revision")).toBe("8");
    expect(put?.body).toMatchObject({ context: "Original context", expiresOn: null });
  });

  test("a date in the past is explained, a stale revision offers the reload, and Cancel leaves the editor", async () => {
    let answer = problem(400);
    stubFetch((r) => (r.method === "PUT" ? answer : jsonResponse(200, { items: [] })));
    const user = userEvent.setup();
    renderWithProviders(<AdrValidity headingId="validity-heading" adr={adrFixture()} />);
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
