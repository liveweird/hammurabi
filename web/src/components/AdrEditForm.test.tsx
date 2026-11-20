import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture } from "../test/adrFixtures";
import { noContent, problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import AdrEditForm from "./AdrEditForm";

describe("AdrEditForm", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("saves the whole document under the held revision, keeps the expiry date and closes", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? noContent() : jsonResponse(200, { items: [] })));
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<AdrEditForm adr={adrFixture({ revision: 7, expiresOn: "2027-05-01" })} onClose={onClose} />);
    await user.type(screen.getByRole("textbox", { name: /^Context/ }), "Why it matters");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const put = stub.find("PUT", "/api/v1/adrs/1");
    expect(put?.headers.get("X-Expected-Revision")).toBe("7");
    expect(put?.body).toEqual({
      name: "Use PostgreSQL",
      problem: "We need a database.",
      context: "Why it matters",
      decision: "",
      alternatives: "",
      consequences: "",
      expiresOn: "2027-05-01",
    });
  });

  test("a refetch that advances the revision while the form is open does not turn the save into a lost update", async () => {
    const stub = stubFetch((r) => (r.method === "PUT" ? noContent() : jsonResponse(200, { items: [] })));
    const onClose = vi.fn();
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<AdrEditForm adr={adrFixture({ revision: 7 })} onClose={onClose} />);
    await user.type(screen.getByRole("textbox", { name: /^Context/ }), "Why it matters");
    rerender(<AdrEditForm adr={adrFixture({ revision: 11, context: "Someone else's text" })} onClose={onClose} />);
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(stub.find("PUT", "/api/v1/adrs/1")?.headers.get("X-Expected-Revision")).toBe("7");
  });

  test("validation blocks an empty name and problem without a request", async () => {
    const stub = stubFetch(() => noContent());
    const user = userEvent.setup();
    renderWithProviders(<AdrEditForm adr={adrFixture()} onClose={vi.fn()} />);
    await user.clear(screen.getByRole("textbox", { name: /^Name/ }));
    await user.clear(screen.getByRole("textbox", { name: /^Problem/ }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("The name must be 1–120 characters")).toBeInTheDocument();
    expect(screen.getByText("Describe the problem or question")).toBeInTheDocument();
    expect(stub.find("PUT", "/api/v1/adrs")).toBeUndefined();
  });

  test("a stale revision shows the reload notice; reloading closes the editor and drops the draft", async () => {
    stubFetch((r) => (r.method === "PUT" ? problem(409, "urn:hammurabi:problem:revision-conflict") : jsonResponse(200, { items: [] })));
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<AdrEditForm adr={adrFixture()} onClose={onClose} />);
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Someone else changed this ADR after you opened it");
    expect(onClose).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Reload the ADR" }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  test("a frozen ADR and a plain failure have their own messages", async () => {
    const stub = stubFetch(() => problem(409, "urn:hammurabi:problem:adr-frozen"));
    const user = userEvent.setup();
    renderWithProviders(<AdrEditForm adr={adrFixture()} onClose={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This ADR is frozen");
    expect(stub.count("PUT", "/api/v1/adrs/1")).toBe(1);
  });

  test("a name clash is reported in words, and Cancel closes without a request", async () => {
    const stub = stubFetch(() => problem(409));
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<AdrEditForm adr={adrFixture()} onClose={onClose} />);
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("An ADR with this name already exists.");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onClose).toHaveBeenCalled();
    expect(stub.count("PUT", "/api/v1/adrs/1")).toBe(1);
  });
});
