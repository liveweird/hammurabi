import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture } from "../test/adrFixtures";
import { noContent, problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import AdrTagsPanel from "./AdrTagsPanel";

const REGISTRY = {
  items: [
    { id: 1, name: "Domain", description: null, tags: ["data", "security"] },
    { id: 2, name: "Scope", description: null, tags: ["team"] },
  ],
};

function serve(save: () => Response = noContent) {
  return stubFetch((r) => {
    if (r.method === "PUT") return save();
    if (r.url === "/api/v1/tag-categories") return jsonResponse(200, REGISTRY);
    return jsonResponse(200, { items: [] });
  });
}

describe("AdrTagsPanel", () => {
  beforeEach(() => localStorage.setItem("hammurabi.auth.token", "fake-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("lists the tags and says when there are none", () => {
    const { unmount } = renderWithProviders(<AdrTagsPanel adr={adrFixture({ tags: [{ name: "data", retired: false }] })} />);
    expect(screen.getByText("data")).toBeInTheDocument();
    unmount();
    renderWithProviders(<AdrTagsPanel adr={adrFixture()} />);
    expect(screen.getByText("No tags")).toBeInTheDocument();
  });

  test("whoever the server does not allow has no Edit button", () => {
    renderWithProviders(<AdrTagsPanel adr={adrFixture({ canEditTags: false })} />);
    expect(screen.queryByRole("button", { name: "Edit tags" })).not.toBeInTheDocument();
  });

  test("the editor offers the registry grouped by category and replaces the whole set under the held revision", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<AdrTagsPanel adr={adrFixture({ revision: 6, tags: [{ name: "data", retired: false }] })} />);
    await user.click(screen.getByRole("button", { name: "Edit tags" }));
    const picker = screen.getByRole("combobox", { name: "Tags" });
    await user.click(picker);
    expect(await screen.findByText("Domain")).toBeInTheDocument();
    expect(screen.getByText("Scope")).toBeInTheDocument();
    await user.click(await screen.findByRole("option", { name: "team" }));
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Save tags" })).not.toBeInTheDocument());
    const put = stub.find("PUT", "/api/v1/adrs/1/tags");
    expect(put?.headers.get("X-Expected-Revision")).toBe("6");
    expect(put?.body).toEqual({ tags: ["data", "team"] });
  });

  test("a refetch that advances the revision while the editor is open does not turn the save into a lost update", async () => {
    const stub = serve();
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<AdrTagsPanel adr={adrFixture({ revision: 6 })} />);
    await user.click(screen.getByRole("button", { name: "Edit tags" }));
    rerender(<AdrTagsPanel adr={adrFixture({ revision: 9 })} />);
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1/tags")).toBeDefined());
    expect(stub.find("PUT", "/api/v1/adrs/1/tags")?.headers.get("X-Expected-Revision")).toBe("6");
  });

  test("a retired tag is not carried into the editor and the hint says it is dropped on save", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<AdrTagsPanel adr={adrFixture({ tags: [{ name: "data", retired: false }, { name: "legacy", retired: true }] })} />);
    expect(screen.getByText("legacy (retired)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Edit tags" }));
    expect(screen.getByText(/dropped when you save/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1/tags")?.body).toEqual({ tags: ["data"] }));
  });

  test("Cancel closes the editor without writing", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<AdrTagsPanel adr={adrFixture()} />);
    await user.click(screen.getByRole("button", { name: "Edit tags" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Edit tags" })).toBeInTheDocument();
    expect(stub.find("PUT", "/api/v1/adrs/1/tags")).toBeUndefined();
  });

  test("a refused set says what to check; a stale revision is the orange reload notice that closes the editor", async () => {
    let answer = () => problem(400);
    serve(() => answer());
    const user = userEvent.setup();
    renderWithProviders(<AdrTagsPanel adr={adrFixture()} />);
    await user.click(screen.getByRole("button", { name: "Edit tags" }));
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    expect(await screen.findByText(/use only tags from the registry/)).toBeInTheDocument();
    answer = () => problem(409, "urn:hammurabi:problem:revision-conflict");
    await user.click(screen.getByRole("button", { name: "Save tags" }));
    await user.click(await screen.findByRole("button", { name: "Reload the ADR" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit tags" })).toBeInTheDocument());
  });

  test("a registry that fails to load is reported inside the editor", async () => {
    stubFetch(() => new Response(null, { status: 500 }));
    const user = userEvent.setup();
    renderWithProviders(<AdrTagsPanel adr={adrFixture()} />);
    await user.click(screen.getByRole("button", { name: "Edit tags" }));
    expect(await screen.findByText("Could not load the tag registry")).toBeInTheDocument();
  });
});
