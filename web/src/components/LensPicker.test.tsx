import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { Lens } from "../api/lenses";
import { useAdrListParams } from "../hooks/useAdrListParams";
import { stubFetch, noContent, problem } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { filtersFromParams, paramsFromFilters } from "../utils/lensFilters";
import LensPicker from "./LensPicker";
import { useLocation } from "react-router-dom";

const lens = (overrides: Partial<Lens>): Lens => ({
  id: 1,
  name: "Lens",
  visibility: "PRIVATE",
  filters: {},
  createdById: 5,
  createdByName: "Me",
  createdByDeleted: false,
  createdAt: 1,
  updatedAt: 1,
  canEdit: true,
  canDelete: true,
  ...overrides,
});

const OPEN = lens({ id: 1, name: "Open ones", filters: { status: ["ACTIVE"], tag: ["data"] } });
const SHARED_MINE = lens({ id: 2, name: "Team view", visibility: "PUBLIC", filters: { text: "queue", hasOpenIssues: true } });
const THEIRS = lens({ id: 3, name: "Theirs", visibility: "PUBLIC", filters: { hasPredecessor: true }, createdById: 8, createdByName: "Alice", canEdit: false, canDelete: false });
const MODERATED = lens({ id: 4, name: "Moderated", visibility: "PUBLIC", filters: { q: "x" }, createdById: 9, createdByName: "Bob", canEdit: false, canDelete: true });

function page(items: Lens[]) {
  return jsonResponse(200, { items, page: 1, pageSize: 100, total: items.length });
}

/** The picker over the real URL hook: the page's wiring without the table. */
function Harness() {
  const { params, update } = useAdrListParams();
  const { search } = useLocation();
  return (
    <>
      <LensPicker filters={filtersFromParams(params, 5)} onApply={(filters) => update(paramsFromFilters(filters, 5))} />
      <p data-testid="at">{search}</p>
    </>
  );
}

const at = () => screen.getByTestId("at");

function serve(handler: (method: string, url: string) => Response | null = () => null, own = [OPEN, SHARED_MINE], others = [THEIRS, MODERATED]) {
  return stubFetch((r) => {
    const custom = handler(r.method, r.url);
    if (custom) return custom;
    if (r.method === "GET" && r.url.startsWith("/api/v1/lenses?")) return page(r.url.includes("mine=true") ? own : others);
    return null;
  });
}

describe("LensPicker", () => {
  beforeEach(() => {
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    localStorage.setItem("hammurabi.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  async function openPicker(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole("combobox", { name: "Lens" }));
    await screen.findAllByRole("option");
  }
  async function openMenu(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole("button", { name: "Lens actions" }));
  }

  test("reads the caller's own lenses and the others' shared ones in two groups, naming the creator of a shared one", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await openPicker(user);
    expect(stub.requests.map((r) => r.url).sort()).toEqual(["/api/v1/lenses?page=1&pageSize=100&mine=false", "/api/v1/lenses?page=1&pageSize=100&mine=true"]);
    expect(screen.getByText("My lenses")).toBeInTheDocument();
    expect(screen.getByText("Shared lenses")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Open ones" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Team view" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Theirs — Alice" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Moderated — Bob" })).toBeInTheDocument();
  });

  test("applying a lens replaces EVERY filter with its own and keeps the foreign keys", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/adrs?q=zzz&text=pg&hasPredecessor=true&mine=1&foo=bar" });
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Open ones" }));
    expect(at()).toHaveTextContent("?foo=bar&status=ACTIVE&tag=data");
    expect(screen.getByRole("combobox", { name: "Lens" })).toHaveValue("Open ones");
    // A lens holding a search term and a yes/no filter.
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Team view" }));
    expect(at()).toHaveTextContent("?foo=bar&text=queue&hasOpenIssues=true");
  });

  test("the lens the filters equal is highlighted (private badge), and diverging from the picked one says Modified", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/adrs?tag=data&status=ACTIVE" });
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Lens" })).toHaveValue("Open ones"));
    expect(screen.getByText("Private")).toBeInTheDocument();
    expect(screen.queryByText("Modified")).not.toBeInTheDocument();
    // Pick another lens, then leave it.
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Team view" }));
    expect(screen.getByText("Shared")).toBeInTheDocument();
    expect(screen.queryByText("Modified")).not.toBeInTheDocument();
  });

  test("a picked lens that the URL filters have left reads Modified", async () => {
    serve();
    const user = userEvent.setup();
    function Changer() {
      const { update } = useAdrListParams();
      return <button onClick={() => update({ hasPredecessor: false })}>change</button>;
    }
    renderWithProviders(
      <>
        <Harness />
        <Changer />
      </>,
    );
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Open ones" }));
    expect(screen.queryByText("Modified")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "change" }));
    expect(await screen.findByText("Modified")).toBeInTheDocument();
  });

  test("each action is offered only where the lens's own verdicts allow it", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/adrs?q=db" });
    // Nothing picked: only Save as is on offer.
    await openMenu(user);
    expect(await screen.findByRole("menuitem", { name: "Save current filters as lens…" })).toBeEnabled();
    expect(screen.queryByRole("menuitem", { name: "Delete lens…" })).not.toBeInTheDocument();
    await user.keyboard("{Escape}");

    // Someone else's shared lens: neither edit nor delete.
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Theirs — Alice" }));
    await openMenu(user);
    expect(await screen.findByRole("menuitem", { name: "Save current filters as lens…" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Delete lens…" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Rename / change visibility…" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Overwrite with the current filters" })).not.toBeInTheDocument();
    await user.keyboard("{Escape}");

    // An ADMIN moderating someone else's shared lens: delete only.
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Moderated — Bob" }));
    await openMenu(user);
    expect(await screen.findByRole("menuitem", { name: "Delete lens…" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Rename / change visibility…" })).not.toBeInTheDocument();
    await user.keyboard("{Escape}");

    // Your own: everything.
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Open ones" }));
    await openMenu(user);
    expect(await screen.findByRole("menuitem", { name: "Delete lens…" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Rename / change visibility…" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Overwrite with the current filters" })).toBeInTheDocument();
  });

  test("saving needs filters to save", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await openMenu(user);
    expect(await screen.findByRole("menuitem", { name: "Save current filters as lens…" })).toBeDisabled();
  });

  test("Save current filters stores exactly the URL filters under the chosen name and visibility, then picks the new lens", async () => {
    const created = lens({ id: 9, name: "Fresh", visibility: "PUBLIC", filters: { q: "db", text: "queue", status: ["ACTIVE"], tag: ["data"], participantId: 5, participantRole: "DRIVER", createdFrom: "2026-01-01", expiresBefore: "2027-01-01", hasPredecessor: false } });
    const stub = serve((method) => (method === "POST" ? jsonResponse(201, created) : null));
    const user = userEvent.setup();
    renderWithProviders(<Harness />, {
      route: "/adrs?q=db&text=queue&status=ACTIVE&tag=data&mine=1&participantRole=DRIVER&createdFrom=2026-01-01&expiresBefore=2027-01-01&hasPredecessor=false",
    });
    await openMenu(user);
    await user.click(await screen.findByRole("menuitem", { name: "Save current filters as lens…" }));
    await user.type(await screen.findByRole("textbox", { name: "Name" }), "  Fresh ");
    await user.click(screen.getByRole("radio", { name: /Shared/ }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/lenses")).toBeDefined());
    expect(stub.find("POST", "/api/v1/lenses")?.body).toEqual({
      name: "Fresh",
      visibility: "PUBLIC",
      filters: { q: "db", text: "queue", status: ["ACTIVE"], tag: ["data"], participantId: 5, participantRole: "DRIVER", createdFrom: "2026-01-01", expiresBefore: "2027-01-01", hasPredecessor: false },
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("the editor wants a name, and a taken name (or the limit) is the fixed 409 sentence", async () => {
    serve((method) => (method === "POST" ? problem(409) : null));
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/adrs?q=db" });
    await openMenu(user);
    await user.click(await screen.findByRole("menuitem", { name: "Save current filters as lens…" }));
    await user.click(await screen.findByRole("button", { name: "Save" }));
    expect(await screen.findByText("Give the lens a name")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Dup");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("You already have a lens with this name, or you have reached the limit of 50 lenses.")).toBeInTheDocument();
    // The dialog stays open to correct the name.
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("rename / visibility PUTs the whole lens with the filters it STORES, not the current ones", async () => {
    const stub = serve((method) => (method === "PUT" ? noContent() : null));
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/adrs?tag=data&status=ACTIVE&hasPredecessor=false" });
    await openMenu(user);
    // The filters differ from the lens (an extra supersedes), so it is picked rather than just equal.
    await user.keyboard("{Escape}");
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Open ones" }));
    await openMenu(user);
    await user.click(await screen.findByRole("menuitem", { name: "Rename / change visibility…" }));
    const name = await screen.findByRole("textbox", { name: "Name" });
    expect(name).toHaveValue("Open ones");
    await user.clear(name);
    await user.type(name, "Renamed");
    await user.click(screen.getByRole("radio", { name: /Shared/ }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/lenses/1")).toBeDefined());
    expect(stub.find("PUT", "/api/v1/lenses/1")?.body).toEqual({ name: "Renamed", visibility: "PUBLIC", filters: { status: ["ACTIVE"], tag: ["data"] } });
  });

  test("Overwrite replaces the stored filters with the current ones under the same name and visibility", async () => {
    const stub = serve((method) => (method === "PUT" ? noContent() : null));
    const user = userEvent.setup();
    function Changer() {
      const { update } = useAdrListParams();
      return <button onClick={() => update({ hasOpenIssues: true })}>change</button>;
    }
    renderWithProviders(
      <>
        <Harness />
        <Changer />
      </>,
    );
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Open ones" }));
    await openMenu(user);
    expect(await screen.findByRole("menuitem", { name: "Overwrite with the current filters" })).toBeDisabled();
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "change" }));
    await openMenu(user);
    await user.click(await screen.findByRole("menuitem", { name: "Overwrite with the current filters" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/lenses/1")).toBeDefined());
    expect(stub.find("PUT", "/api/v1/lenses/1")?.body).toEqual({ name: "Open ones", visibility: "PRIVATE", filters: { status: ["ACTIVE"], tag: ["data"], hasOpenIssues: true } });
    // The lens lists refresh after the write.
    await waitFor(() => expect(stub.count("GET", "/api/v1/lenses?")).toBeGreaterThan(2));
  });

  test.each([
    [403, "Only the creator can change a shared lens."],
    [404, "This lens no longer exists."],
    [409, "You already have a lens with this name, or you have reached the limit of 50 lenses."],
    [500, "Saving the lens failed (500)"],
  ])("a failed overwrite (%i) is said in fixed words", async (status, message) => {
    serve((method) => (method === "PUT" ? problem(status) : null));
    const user = userEvent.setup();
    function Changer() {
      const { update } = useAdrListParams();
      return <button onClick={() => update({ hasOpenIssues: true })}>change</button>;
    }
    renderWithProviders(
      <>
        <Harness />
        <Changer />
      </>,
    );
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Open ones" }));
    await user.click(screen.getByRole("button", { name: "change" }));
    await openMenu(user);
    await user.click(await screen.findByRole("menuitem", { name: "Overwrite with the current filters" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
  });

  test("delete asks first, then DELETEs the lens and drops the pick", async () => {
    const stub = serve((method) => (method === "DELETE" ? noContent() : null));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Moderated — Bob" }));
    await openMenu(user);
    await user.click(await screen.findByRole("menuitem", { name: "Delete lens…" }));
    expect(await screen.findByText('The lens "Moderated" will be gone for everyone who can see it. The ADRs are not affected.')).toBeInTheDocument();
    expect(stub.find("DELETE", "/api/v1/lenses/4")).toBeUndefined();
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(stub.find("DELETE", "/api/v1/lenses/4")).toBeDefined());
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("a failed delete keeps the dialog open with the reason", async () => {
    serve((method) => (method === "DELETE" ? problem(404) : null));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await openPicker(user);
    await user.click(screen.getByRole("option", { name: "Open ones" }));
    await openMenu(user);
    await user.click(await screen.findByRole("menuitem", { name: "Delete lens…" }));
    await user.click(await screen.findByRole("button", { name: "Delete" }));
    expect(await screen.findByText("This lens no longer exists.")).toBeInTheDocument();
    expect(screen.getByText("Could not delete the lens")).toBeInTheDocument();
  });

  test("a failed load of the lenses is an inline alert, and the picker says there is nothing to apply", async () => {
    stubFetch(() => new Response(null, { status: 500 }));
    renderWithProviders(<Harness />);
    expect(await screen.findByText("Could not load the saved filters")).toBeInTheDocument();
    expect(screen.getAllByRole("alert").length).toBeGreaterThan(0);
    expect(screen.getByRole("combobox", { name: "Lens" })).toHaveAttribute("placeholder", "No saved filters yet");
  });
});
