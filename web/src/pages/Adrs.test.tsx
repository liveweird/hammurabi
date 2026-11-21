import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import type { AdrPage } from "../api/adrs";
import { adrPageFixture } from "../test/adrFixtures";
import { stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { fireEvent, renderWithProviders, screen, waitFor, within } from "../test/render";
import { headingOutline } from "../test/headings";
import { usePinnedBrowserTimeZone } from "../test/timeZone";
import Adrs from "./Adrs";

const saveFile = vi.fn();
vi.mock("../utils/saveFile", () => ({ saveFile: (download: unknown) => saveFile(download) }));

const ROW = {
  id: 7,
  number: 7,
  key: "ADR-0007",
  name: "Use PostgreSQL",
  status: "IN_REVIEW" as const,
  expiresOn: null,
  revision: 2,
  createdAt: Date.UTC(2026, 10, 1),
  updatedAt: Date.UTC(2026, 10, 3, 12),
  statusChangedAt: Date.UTC(2026, 10, 2),
  driver: { userId: 5, name: "Dana Driver", role: "DRIVER" as const },
  approver: { userId: 6, name: "Alex Approver", role: "APPROVER" as const },
  tags: [{ name: "data", retired: false }, { name: "old-tag", retired: true }],
  openIssueCount: 0,
};
const ROW_WITHOUT_PEOPLE = { ...ROW, id: 8, number: 8, key: "ADR-0008", name: "Pick a queue", status: "ACTIVE" as const, driver: null, approver: null, tags: [], openIssueCount: 3 };

const REGISTRY = {
  items: [
    { id: 1, name: "Domain", description: null, tags: ["data", "security"] },
    { id: 2, name: "Scope", description: null, tags: ["team"] },
  ],
};

/** The list and, beside it, the registry the tag filter's picker reads and the (empty) lenses the lens picker reads. */
const serve = (page: AdrPage) =>
  stubFetch((r) => {
    if (r.url.startsWith("/api/v1/tag-categories")) return jsonResponse(200, REGISTRY);
    if (r.url.startsWith("/api/v1/lenses")) return jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 });
    return jsonResponse(200, page);
  });

function Probe() {
  const { pathname, search } = useLocation();
  return <p data-testid="at">{pathname + search}</p>;
}

function renderList(route = "/adrs") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/adrs" element={<Adrs />} />
        <Route path="/adrs/new" element={<Probe />} />
      </Routes>
      <Probe />
    </>,
    { route },
  );
}

const settle = (ms = 450) => new Promise((resolve) => setTimeout(resolve, ms));

const listUrls = (stub: ReturnType<typeof stubFetch>) => stub.requests.filter((r) => r.url.startsWith("/api/v1/adrs?")).map((r) => r.url);

describe("Adrs list page", () => {
  usePinnedBrowserTimeZone("Pacific/Auckland");
  let page: AdrPage;
  beforeEach(() => {
    page = adrPageFixture([ROW, ROW_WITHOUT_PEOPLE]);
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    localStorage.setItem("hammurabi.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("lists key, name link, status badge, driver, approver and the updated date, newest first by default", async () => {
    const stub = serve(page);
    renderList();
    const link = await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    expect(link).toHaveAttribute("href", "/adrs/7");
    const row = link.closest("tr")!;
    expect(within(row).getByText("ADR-0007")).toBeInTheDocument();
    expect(within(row).getByText("In review")).toBeInTheDocument();
    expect(within(row).getByText("Dana Driver")).toBeInTheDocument();
    expect(within(row).getByText("Alex Approver")).toBeInTheDocument();
    expect(within(row).getByText("2026-11-04")).toBeInTheDocument();
    // A row with nobody recorded shows dashes.
    const other = screen.getByRole("link", { name: "Open ADR-0008: Pick a queue" }).closest("tr")!;
    expect(within(other).getAllByText("—")).toHaveLength(3);
    // The open issues are a badge, none is a dash: the first row has no issues, the second three.
    expect(within(other).getByText("Open issues: 3")).toBeInTheDocument();
    expect(within(row).queryByText(/^Open issues:/)).not.toBeInTheDocument();
    expect(within(row).getAllByText("—")).toHaveLength(1);
    // The tags are neutral pills; a retired one is marked for assistive technology.
    expect(within(row).getByText("data")).toBeInTheDocument();
    expect(within(row).getByText("old-tag (retired)")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Tags" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Open issues" })).toBeInTheDocument();
    expect(listUrls(stub)).toEqual(["/api/v1/adrs?page=1&pageSize=20&sort=-number"]);
    expect(headingOutline()).toEqual([[2, "ADRs"]]);
    expect(screen.getByRole("table", { name: "ADRs" })).toBeInTheDocument();
  });

  describe("Export (ZIP)", () => {
    const zip = () =>
      new Response("zip", { headers: { "Content-Disposition": 'attachment; filename="adr-export-2026-11-21.zip"' } });
    const serveWithExport = (handler: (url: string) => Response) =>
      stubFetch((r) => {
        if (r.url.startsWith("/api/v1/adrs/export")) return handler(r.url);
        if (r.url.startsWith("/api/v1/tag-categories")) return jsonResponse(200, REGISTRY);
        if (r.url.startsWith("/api/v1/lenses")) return jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 });
        return jsonResponse(200, page);
      });
    afterEach(() => saveFile.mockReset());

    test("downloads exactly what the filters select - their names, no paging or sort - and saves the file under the server's name", async () => {
      const stub = serveWithExport(zip);
      renderList("/adrs?status=ACTIVE&status=IN_REVIEW&tag=data&hasOpenIssues=true&text=queue&page=2");
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      await userEvent.click(screen.getByRole("button", { name: "Export (ZIP)" }));
      await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(1));
      expect(stub.requests.filter((r) => r.url.startsWith("/api/v1/adrs/export")).map((r) => r.url)).toEqual([
        "/api/v1/adrs/export?status=ACTIVE&status=IN_REVIEW&tag=data&text=queue&hasOpenIssues=true",
      ]);
      expect(saveFile.mock.calls[0]?.[0]).toMatchObject({ fileName: "adr-export-2026-11-21.zip" });
    });

    test("an unfiltered list exports everything (no query)", async () => {
      const stub = serveWithExport(zip);
      renderList();
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      await userEvent.click(screen.getByRole("button", { name: "Export (ZIP)" }));
      await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(1));
      expect(stub.find("GET", "/api/v1/adrs/export")?.url).toBe("/api/v1/adrs/export?");
    });

    test("a refusal (more than 500 match) is said inline and nothing is saved", async () => {
      serveWithExport(() => jsonResponse(400, { type: "about:blank", title: "Bad Request", status: 400, detail: "too many", instance: "/x" }));
      renderList();
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      await userEvent.click(screen.getByRole("button", { name: "Export (ZIP)" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("an export holds at most 500 ADRs");
      expect(saveFile).not.toHaveBeenCalled();
    });

    test("is disabled while a date range is inverted (no request would be valid)", async () => {
      serveWithExport(zip);
      renderList("/adrs?updatedFrom=2026-06-30&updatedTo=2026-06-01");
      expect(await screen.findByRole("button", { name: "Export (ZIP)" })).toBeDisabled();
    });
  });

  test("a URL with tags reproduces them in the request, and the picker writes them into the URL", async () => {
    const stub = serve(page);
    const user = userEvent.setup();
    renderList("/adrs?tag=data");
    await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    await waitFor(() => expect(listUrls(stub)).toContain("/api/v1/adrs?page=1&pageSize=20&sort=-number&tag=data"));
    await user.click(screen.getByRole("button", { name: /^Filters/ }));
    await user.click(screen.getByRole("combobox", { name: "Tags" }));
    expect(await screen.findByText("Scope")).toBeInTheDocument();
    await user.click(await screen.findByRole("option", { name: "team" }));
    expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent("/adrs?tag=data&tag=team");
    await waitFor(() => expect(listUrls(stub).some((url) => url.endsWith("&tag=data&tag=team"))).toBe(true));
    await user.click(screen.getByLabelText("Clear tag filter"));
    expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent(/^\/adrs$/);
  });

  test("the tag filter stops at the server's limit of twenty tags", async () => {
    const stub = serve(page);
    const user = userEvent.setup();
    const names = Array.from({ length: 20 }, (_, i) => `tag-${i + 1}`);
    renderList(`/adrs?${names.map((name) => `tag=${name}`).join("&")}`);
    await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    await user.click(screen.getByRole("button", { name: /^Filters/ }));
    const before = listUrls(stub).length;
    await user.click(screen.getByRole("combobox", { name: "Tags" }));
    const option = await screen.findByRole("option", { name: "team" });
    await user.click(option);
    expect(listUrls(stub)).toHaveLength(before);
    expect(screen.getAllByTestId("at").at(-1)).not.toHaveTextContent("tag=team");
  });

  test("New ADR leads to the create page", async () => {
    serve(page);
    const user = userEvent.setup();
    renderList();
    await user.click(await screen.findByRole("link", { name: "New ADR" }));
    expect(screen.getAllByTestId("at")[0]).toHaveTextContent("/adrs/new");
  });

  test("a URL with filters reproduces them in the request: q, repeated status, my ADRs and my role", async () => {
    const stub = serve(page);
    renderList("/adrs?q=db&status=ACTIVE&status=IN_REVIEW&mine=1&role=DRIVER");
    await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    await waitFor(() => expect(listUrls(stub)).toContain("/api/v1/adrs?page=1&pageSize=20&sort=-number&status=ACTIVE&status=IN_REVIEW&q=db&participant=5&role=DRIVER"));
  });

  test("the panel writes the filters into the URL and the list follows", async () => {
    const stub = serve(page);
    const user = userEvent.setup();
    renderList();
    await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    await user.click(screen.getByRole("button", { name: "Filters" }));

    await user.type(screen.getByLabelText("Name or key"), "pg");
    expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent("/adrs?q=pg");
    await waitFor(() => expect(listUrls(stub).some((url) => url.includes("&q=pg"))).toBe(true));
    expect(screen.getByRole("button", { name: /Filters/ })).toHaveTextContent("1");

    await user.click(screen.getByRole("combobox", { name: "Status" }));
    await user.click(await screen.findByRole("option", { name: "Active" }));
    expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent("/adrs?q=pg&status=ACTIVE");

    await user.click(screen.getByRole("switch", { name: "Only ADRs I take part in" }));
    expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent("mine=1");
    await waitFor(() => expect(listUrls(stub).some((url) => url.includes("participant=5") && !url.includes("role="))).toBe(true));

    await user.click(screen.getByRole("combobox", { name: "My role" }));
    await user.click(await screen.findByRole("option", { name: "Approver" }));
    expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent("role=APPROVER");
    await waitFor(() => expect(listUrls(stub).some((url) => url.includes("participant=5&role=APPROVER"))).toBe(true));

    // Switching "mine" off drops the role with it.
    await user.click(screen.getByRole("switch", { name: "Only ADRs I take part in" }));
    expect(screen.getAllByTestId("at").at(-1)).not.toHaveTextContent("role=");
  });

  test("the role filter is off until 'mine' is on, and clearing the name filter empties the URL key", async () => {
    serve(page);
    const user = userEvent.setup();
    renderList("/adrs?q=db");
    await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    await user.click(screen.getByRole("button", { name: /^Filters/ }));
    expect(screen.getByRole("combobox", { name: "My role" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Clear name or key filter" }));
    expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent(/^\/adrs$/);
  });

  test("an unfiltered empty list and a filtered one say different things", async () => {
    serve(adrPageFixture([]));
    const { unmount } = renderList();
    expect(await screen.findByText("No ADRs yet")).toBeInTheDocument();
    unmount();
    renderList("/adrs?status=CANCELLED");
    expect(await screen.findByText("No ADRs match these filters")).toBeInTheDocument();
  });

  test("a failed load is the alert above the table", async () => {
    stubFetch(() => new Response(null, { status: 500 }));
    renderList();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.getByText("Could not load the ADRs")).toBeInTheDocument();
  });

  test("sorting by a column re-queries with that sort", async () => {
    const stub = serve(page);
    const user = userEvent.setup();
    renderList();
    await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    await user.click(screen.getByRole("button", { name: /^Status/ }));
    await waitFor(() => expect(listUrls(stub).at(-1)).toContain("sort=status"));
    await user.click(screen.getByRole("button", { name: /^Key/ }));
    await waitFor(() => expect(listUrls(stub).at(-1)).toContain("sort=number"));
  });

  describe("full-text search", () => {
    const MATCHED = {
      ...ROW,
      match: {
        name: [{ text: "Use ", hit: false }, { text: "Postgre", hit: true }, { text: "SQL", hit: false }],
        snippet: [
          { text: "We chose ", hit: false },
          { text: "Postgre", hit: true },
          { text: 'SQL <img src=x onerror="alert(1)"> for the data', hit: false },
        ],
      },
    };
    const NAME_ONLY = { ...ROW_WITHOUT_PEOPLE, match: { name: [{ text: "Pick a queue", hit: true }], snippet: [] } };

    test("the box commits a trimmed term to the URL and the request after the debounce, and sends no sort so the server ranks", async () => {
      const stub = serve(adrPageFixture([MATCHED, NAME_ONLY]));
      const user = userEvent.setup();
      renderList();
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      await user.type(screen.getByRole("textbox", { name: "Search" }), " postgres ");
      expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent(/^\/adrs$/);
      await waitFor(() => expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent("/adrs?text=postgres"));
      await waitFor(() => expect(listUrls(stub).at(-1)).toBe("/api/v1/adrs?page=1&pageSize=20&text=postgres"));
      // The box keeps what was typed (the trailing space is not eaten under the user's cursor).
      expect(screen.getByRole("textbox", { name: "Search" })).toHaveValue(" postgres ");
      expect(screen.getByText("Sorted by relevance. Click a column heading to sort by it instead.")).toBeInTheDocument();
      expect(screen.getByRole("columnheader", { name: "Key" })).toHaveAttribute("aria-sort", "none");

      // Picking a column takes the order over; the relevance hint goes.
      await user.click(screen.getByRole("button", { name: /^Status/ }));
      await waitFor(() => expect(listUrls(stub).at(-1)).toBe("/api/v1/adrs?page=1&pageSize=20&sort=status&text=postgres"));
      expect(screen.queryByText(/Sorted by relevance/)).not.toBeInTheDocument();
      expect(screen.getByRole("columnheader", { name: "Status" })).toHaveAttribute("aria-sort", "ascending");

      // Clearing the search returns to the ordinary list (a sort stays whatever the user left).
      await user.click(screen.getByRole("button", { name: "Clear search" }));
      await waitFor(() => expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent(/^\/adrs$/));
      await waitFor(() => expect(listUrls(stub).at(-1)).toBe("/api/v1/adrs?page=1&pageSize=20&sort=status"));
    });

    test("a single character is not searched: the URL and the request stay as they were and the box hints", async () => {
      const stub = serve(page);
      const user = userEvent.setup();
      renderList();
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      await user.type(screen.getByRole("textbox", { name: "Search" }), "p");
      expect(screen.getByText("Type at least 2 characters, including a letter or a digit, to search.")).toBeInTheDocument();
      await settle();
      expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent(/^\/adrs$/);
      expect(listUrls(stub)).toEqual(["/api/v1/adrs?page=1&pageSize=20&sort=-number"]);
      await user.type(screen.getByRole("textbox", { name: "Search" }), "g");
      expect(screen.queryByText("Type at least 2 characters, including a letter or a digit, to search.")).not.toBeInTheDocument();
      await waitFor(() => expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent("/adrs?text=pg"));
    });

    test("a URL with a term fills the box, searches, and keeps the page size at the server's cap", async () => {
      localStorage.setItem("hammurabi.viewSettings.adrs.paging", JSON.stringify({ sortField: "number", sortDir: "desc", pageSize: 60 }));
      const stub = serve(page);
      const user = userEvent.setup();
      renderList("/adrs?text=queue");
      expect(screen.getByRole("textbox", { name: "Search" })).toHaveValue("queue");
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      expect(listUrls(stub)).toEqual(["/api/v1/adrs?page=1&pageSize=20&text=queue"]);
      // Only the sizes the server accepts are on offer while searching.
      await user.click(screen.getByLabelText("Rows per page", { selector: "input" }));
      expect(await screen.findByRole("option", { name: "20 / page" })).toBeInTheDocument();
      expect(screen.queryByRole("option", { name: "60 / page" })).not.toBeInTheDocument();
    });

    test("a hand-edited one-character term is no search at all", async () => {
      const stub = serve(page);
      renderList("/adrs?text=x");
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      expect(listUrls(stub)).toEqual(["/api/v1/adrs?page=1&pageSize=20&sort=-number"]);
    });

    test("rows show the name and the snippet with the hits marked, as text; the link keeps its plain name", async () => {
      serve(adrPageFixture([MATCHED, NAME_ONLY]));
      const { container } = renderList("/adrs?text=postgres");
      const link = await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      expect(link).toHaveTextContent("Use PostgreSQL");
      const row = link.closest("tr")!;
      const marks = [...row.querySelectorAll("mark")].map((mark) => mark.textContent);
      expect(marks).toEqual(["Postgre", "Postgre"]);
      expect(link.querySelectorAll("mark")).toHaveLength(1);
      // The snippet is text: the markup-like part is shown, never parsed.
      expect(within(row).getByText(/for the data/)).toHaveTextContent('We chose PostgreSQL <img src=x onerror="alert(1)"> for the data');
      expect(container.querySelector("img")).toBeNull();
      // A row that matched on its name only has no snippet line.
      const other = screen.getByRole("link", { name: "Open ADR-0008: Pick a queue" }).closest("tr")!;
      expect(other.querySelectorAll("mark")).toHaveLength(1);
      expect(within(other).queryByText(/We chose/)).not.toBeInTheDocument();
    });

    test("rows of an unsearched list have no marks", async () => {
      serve(page);
      renderList();
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      expect(document.querySelector("mark")).toBeNull();
    });

    test("a search the server timed out tells the user to narrow it; a busy server to retry", async () => {
      const typed = (type: string) => jsonResponse(429, { type, title: "Too many requests", status: 429 });
      stubFetch((r) => (r.url.startsWith("/api/v1/adrs?") ? typed("urn:hammurabi:problem:search-timeout") : jsonResponse(200, { items: [] })));
      const { unmount } = renderList("/adrs?text=queue");
      expect(await screen.findByRole("alert")).toHaveTextContent("The search took too long. Narrow it - add a word or a filter - and try again.");
      unmount();
      stubFetch((r) => (r.url.startsWith("/api/v1/adrs?") ? typed("urn:hammurabi:problem:search-busy") : jsonResponse(200, { items: [] })));
      renderList("/adrs?text=queue");
      expect(await screen.findByRole("alert")).toHaveTextContent("Search is busy right now. Try again in a moment.");
    });
  });

  describe("the filter panel", () => {
    const openPanel = async (route = "/adrs") => {
      const stub = serve(page);
      const user = userEvent.setup();
      renderList(route);
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      await user.click(screen.getByRole("button", { name: /^Filters/ }));
      return { stub, user };
    };

    test("a URL with the new filters reproduces them in the request", async () => {
      const stub = serve(page);
      renderList(
        "/adrs?createdFrom=2026-01-01&createdTo=2026-02-01&updatedFrom=2026-03-01&updatedTo=2026-04-01&expiresBefore=2027-01-01&hasOpenIssues=true&supersedes=false&supersededBy=false",
      );
      await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
      expect(listUrls(stub)).toEqual([
        "/api/v1/adrs?page=1&pageSize=20&sort=-number&createdFrom=2026-01-01&createdTo=2026-02-01&updatedFrom=2026-03-01&updatedTo=2026-04-01&expiresBefore=2027-01-01&hasOpenIssues=true&supersedes=false&supersededBy=false",
      ]);
      // Six panel filters are active: the created range, the updated range, the expiry bound and the three yes/no ones.
      expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("6");
    });

    test("the date fields and the any / yes / no selects write their URL keys and the request follows", async () => {
      const { stub, user } = await openPanel();
      fireEvent.change(screen.getByLabelText("Created from"), { target: { value: "2026-05-01" } });
      fireEvent.change(screen.getByLabelText("Created to"), { target: { value: "2026-05-31" } });
      fireEvent.change(screen.getByLabelText("Updated from"), { target: { value: "2026-06-01" } });
      fireEvent.change(screen.getByLabelText("Updated to"), { target: { value: "2026-06-30" } });
      fireEvent.change(screen.getByLabelText("Expires before"), { target: { value: "2027-01-01" } });
      await user.click(screen.getByRole("combobox", { name: "Has open issues" }));
      await user.click(await screen.findByRole("option", { name: "Yes" }));
      await user.click(screen.getByRole("combobox", { name: "Replaces another ADR" }));
      await user.click(await screen.findByRole("option", { name: "No" }));
      await user.click(screen.getByRole("combobox", { name: "Has a successor" }));
      await user.click(await screen.findByRole("option", { name: "Yes" }));
      expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent(
        "/adrs?createdFrom=2026-05-01&createdTo=2026-05-31&updatedFrom=2026-06-01&updatedTo=2026-06-30&expiresBefore=2027-01-01&hasOpenIssues=true&supersedes=false&supersededBy=true",
      );
      await waitFor(() =>
        expect(listUrls(stub).at(-1)).toBe(
          "/api/v1/adrs?page=1&pageSize=20&sort=-number&createdFrom=2026-05-01&createdTo=2026-05-31&updatedFrom=2026-06-01&updatedTo=2026-06-30&expiresBefore=2027-01-01&hasOpenIssues=true&supersedes=false&supersededBy=true",
        ),
      );
      // "Any" removes the key again.
      await user.click(screen.getByRole("combobox", { name: "Has open issues" }));
      await user.click(await screen.findByRole("option", { name: "Any" }));
      expect(screen.getAllByTestId("at").at(-1)).not.toHaveTextContent("hasOpenIssues");
      fireEvent.change(screen.getByLabelText("Created from"), { target: { value: "" } });
      expect(screen.getAllByTestId("at").at(-1)).not.toHaveTextContent("createdFrom");
    });

    test("an inverted range is flagged on the field and no request goes out for it", async () => {
      const { stub } = await openPanel("/adrs?updatedFrom=2026-06-30");
      const before = listUrls(stub).length;
      fireEvent.change(screen.getByLabelText("Updated to"), { target: { value: "2026-06-01" } });
      expect(await screen.findByText("The start date is after the end date.")).toBeInTheDocument();
      await settle();
      expect(listUrls(stub)).toHaveLength(before);
    });

    test("a participant carried by an applied lens shows as a removable chip, and the role stays selectable", async () => {
      const { user } = await openPanel("/adrs?participant=9&role=DRIVER");
      expect(screen.getByText("Participant: user 9")).toBeInTheDocument();
      expect(screen.getByRole("combobox", { name: "My role" })).toBeEnabled();
      await user.click(screen.getByRole("button", { name: "Clear participant filter" }));
      expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent(/^\/adrs$/);
    });

    test("Clear all filters empties the URL, the search box included", async () => {
      const { user } = await openPanel("/adrs?text=queue&status=ACTIVE&hasOpenIssues=true");
      await user.click(screen.getByRole("button", { name: "Clear all filters" }));
      expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent(/^\/adrs$/);
      expect(screen.getByRole("textbox", { name: "Search" })).toHaveValue("");
      expect(screen.queryByRole("button", { name: "Clear all filters" })).not.toBeInTheDocument();
    });
  });
});
