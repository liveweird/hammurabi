import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { noContent, problem, stubFetch, type StubRequest } from "../test/fetchStub";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import Tags from "./Tags";

const REGISTRY = {
  items: [
    { id: 1, name: "Domain", description: "The technical area", revision: 3, tags: ["data", "security"] },
    { id: 2, name: "Scope", description: null, revision: 5, tags: ["team"] },
  ],
};

type Mutations = { post?: () => Response; put?: () => Response; del?: () => Response };

function serve(items = REGISTRY.items, mutations: Mutations = {}) {
  return stubFetch((r: StubRequest) => {
    if (r.method === "POST") return (mutations.post ?? (() => jsonResponse(201, { id: 9, name: "New", description: null, tags: ["x"] })))();
    if (r.method === "PUT") return (mutations.put ?? noContent)();
    if (r.method === "DELETE") return (mutations.del ?? noContent)();
    return jsonResponse(200, { items });
  });
}

describe("Tags page", () => {
  beforeEach(() => {
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    localStorage.setItem("hammurabi.auth.roles", JSON.stringify(["ADMIN"]));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("lists every category with its description and its tags as pills", async () => {
    serve();
    renderWithProviders(<Tags />);
    const row = (await screen.findByText("Domain")).closest("tr")!;
    expect(within(row).getByText("The technical area")).toBeInTheDocument();
    expect(within(row).getByText("data")).toBeInTheDocument();
    expect(within(row).getByText("security")).toBeInTheDocument();
    const scope = screen.getByText("Scope").closest("tr")!;
    expect(within(scope).getByText("—")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Tags" })).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Tags"]]);
  });

  test("an empty registry and a failed load say so", async () => {
    serve([]);
    const { unmount } = renderWithProviders(<Tags />);
    expect(await screen.findByText("No tag categories yet")).toBeInTheDocument();
    unmount();
    stubFetch(() => new Response(null, { status: 500 }));
    renderWithProviders(<Tags />);
    expect(await screen.findByText("Could not load the tag categories")).toBeInTheDocument();
    expect(screen.queryByText("No tag categories yet")).not.toBeInTheDocument();
  });

  test("a category is created with normalised tags and the registry refreshes", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "New category" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Name"), "Concern");
    await user.type(within(dialog).getByLabelText("Description"), " cross-cutting ");
    await user.type(within(dialog).getByRole("combobox", { name: "Tags" }), "Cost  Control,Latency,");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/tag-categories")).toBeDefined());
    expect(stub.find("POST", "/api/v1/tag-categories")?.body).toEqual({ name: "Concern", description: "cross-cutting", tags: ["cost control", "latency"] });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(stub.count("GET", "/api/v1/tag-categories")).toBeGreaterThan(1));
  });

  test("an invalid form is blocked on the client with the fields' words", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "New category" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));
    expect(await within(dialog).findByText("Name must be 1–60 characters")).toBeInTheDocument();
    expect(within(dialog).getByText("Add at least one tag")).toBeInTheDocument();
    expect(stub.find("POST", "/api/v1/tag-categories")).toBeUndefined();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("an edit starts from the category and PUTs the whole replacement", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Domain" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit Domain" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("Name")).toHaveValue("Domain");
    await user.type(within(dialog).getByRole("combobox", { name: "Tags" }), "Ops{Enter}");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/tag-categories/1")).toBeDefined());
    expect(stub.find("PUT", "/api/v1/tag-categories/1")?.body).toEqual({
      name: "Domain",
      description: "The technical area",
      tags: ["data", "security", "ops"],
    });
    expect(stub.find("PUT", "/api/v1/tag-categories/1")?.headers.get("X-Expected-Revision")).toBe("3");
  });

  test("a stale edit is refused in words, keeps the dialog and re-reads the registry", async () => {
    const stub = serve(REGISTRY.items, { put: () => problem(409, "urn:hammurabi:problem:revision-conflict") });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Domain" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit Domain" }));
    const dialog = await screen.findByRole("dialog");
    const reads = stub.count("GET", "/api/v1/tag-categories");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText(/Another administrator changed this category after you opened it/)).toBeInTheDocument();
    expect(within(dialog).queryByText("The category name or one of its tags is already in use")).not.toBeInTheDocument();
    await waitFor(() => expect(stub.count("GET", "/api/v1/tag-categories")).toBeGreaterThan(reads));
  });

  test("a refused save keeps the dialog open with the fixed words", async () => {
    serve(REGISTRY.items, { post: () => problem(409) });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "New category" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Name"), "Domain");
    await user.type(within(dialog).getByRole("combobox", { name: "Tags" }), "data{Enter}");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));
    expect(await within(dialog).findByText("The category name or one of its tags is already in use")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  test("deleting asks first, says what happens to the tags and then DELETEs", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Scope" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Scope" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete tag category?" });
    expect(within(dialog).getByText(/keep them, marked as retired/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(stub.find("DELETE", "/api/v1/tag-categories/2")).toBeDefined());
    expect(stub.find("DELETE", "/api/v1/tag-categories/2")?.headers.get("X-Expected-Revision")).toBe("5");
  });

  test("a stale delete deletes nothing, says why and re-reads the registry", async () => {
    const stub = serve(REGISTRY.items, { del: () => problem(409, "urn:hammurabi:problem:revision-conflict") });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Scope" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Scope" }));
    const reads = stub.count("GET", "/api/v1/tag-categories");
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText(/was changed after you opened it, so it was not deleted/)).toBeInTheDocument();
    await waitFor(() => expect(stub.count("GET", "/api/v1/tag-categories")).toBeGreaterThan(reads));
  });

  test("a failed delete reports in the dialog", async () => {
    serve(REGISTRY.items, { del: () => problem(404) });
    const user = userEvent.setup();
    renderWithProviders(<Tags />);
    await user.click(await screen.findByRole("button", { name: "Operations for Scope" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Scope" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("This category no longer exists — reload the page")).toBeInTheDocument();
  });
});
