import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import type { AdrPage } from "../api/adrs";
import { adrPageFixture } from "../test/adrFixtures";
import { stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { headingOutline } from "../test/headings";
import Adrs from "./Adrs";

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
};
const ROW_WITHOUT_PEOPLE = { ...ROW, id: 8, number: 8, key: "ADR-0008", name: "Pick a queue", status: "ACTIVE" as const, driver: null, approver: null };

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

const listUrls = (stub: ReturnType<typeof stubFetch>) => stub.requests.filter((r) => r.url.startsWith("/api/v1/adrs?")).map((r) => r.url);

describe("Adrs list page", () => {
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
    const stub = stubFetch(() => jsonResponse(200, page));
    renderList();
    const link = await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    expect(link).toHaveAttribute("href", "/adrs/7");
    const row = link.closest("tr")!;
    expect(within(row).getByText("ADR-0007")).toBeInTheDocument();
    expect(within(row).getByText("In review")).toBeInTheDocument();
    expect(within(row).getByText("Dana Driver")).toBeInTheDocument();
    expect(within(row).getByText("Alex Approver")).toBeInTheDocument();
    expect(within(row).getByText("2026-11-03")).toBeInTheDocument();
    // A row with nobody recorded shows dashes.
    const other = screen.getByRole("link", { name: "Open ADR-0008: Pick a queue" }).closest("tr")!;
    expect(within(other).getAllByText("—")).toHaveLength(2);
    expect(listUrls(stub)).toEqual(["/api/v1/adrs?page=1&pageSize=20&sort=-number"]);
    expect(headingOutline()).toEqual([[2, "ADRs"]]);
    expect(screen.getByRole("table", { name: "ADRs" })).toBeInTheDocument();
  });

  test("New ADR leads to the create page", async () => {
    stubFetch(() => jsonResponse(200, page));
    const user = userEvent.setup();
    renderList();
    await user.click(await screen.findByRole("link", { name: "New ADR" }));
    expect(screen.getAllByTestId("at")[0]).toHaveTextContent("/adrs/new");
  });

  test("a URL with filters reproduces them in the request: q, repeated status, my ADRs and my role", async () => {
    const stub = stubFetch(() => jsonResponse(200, page));
    renderList("/adrs?q=db&status=ACTIVE&status=IN_REVIEW&mine=1&role=DRIVER");
    await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    await waitFor(() => expect(listUrls(stub)).toContain("/api/v1/adrs?page=1&pageSize=20&sort=-number&status=ACTIVE&status=IN_REVIEW&q=db&participant=5&role=DRIVER"));
  });

  test("the panel writes the filters into the URL and the list follows", async () => {
    const stub = stubFetch(() => jsonResponse(200, page));
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
    stubFetch(() => jsonResponse(200, page));
    const user = userEvent.setup();
    renderList("/adrs?q=db");
    await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    await user.click(screen.getByRole("button", { name: /^Filters/ }));
    expect(screen.getByRole("combobox", { name: "My role" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Clear name or key filter" }));
    expect(screen.getAllByTestId("at").at(-1)).toHaveTextContent(/^\/adrs$/);
  });

  test("an unfiltered empty list and a filtered one say different things", async () => {
    stubFetch(() => jsonResponse(200, adrPageFixture([])));
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
    const stub = stubFetch(() => jsonResponse(200, page));
    const user = userEvent.setup();
    renderList();
    await screen.findByRole("link", { name: "Open ADR-0007: Use PostgreSQL" });
    await user.click(screen.getByRole("button", { name: /^Status/ }));
    await waitFor(() => expect(listUrls(stub).at(-1)).toContain("sort=status"));
    await user.click(screen.getByRole("button", { name: /^Key/ }));
    await waitFor(() => expect(listUrls(stub).at(-1)).toContain("sort=number"));
  });
});
