import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { adrFixture } from "../test/adrFixtures";
import { problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { headingOutline } from "../test/headings";
import { renderWithProviders, screen, waitFor } from "../test/render";
import CreateAdr from "./CreateAdr";

const DIRECTORY = {
  items: [
    { id: 5, name: "Dana Driver", email: "dana@example.test" },
    { id: 6, name: "Alex Approver", email: "alex@example.test" },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};

function Probe() {
  const { pathname } = useLocation();
  return <p>at {pathname}</p>;
}

function renderCreate() {
  return renderWithProviders(
    <Routes>
      <Route path="/adrs/new" element={<CreateAdr />} />
      <Route path="/adrs" element={<Probe />} />
      <Route path="/adrs/:id" element={<Probe />} />
    </Routes>,
    { route: "/adrs/new" },
  );
}

function serve(createAnswer: () => Response = () => jsonResponse(201, adrFixture({ id: 12 }))) {
  return stubFetch((r) => {
    if (r.method === "POST") return createAnswer();
    if (r.url.startsWith("/api/v1/users/directory")) return jsonResponse(200, DIRECTORY);
    return null;
  });
}

describe("CreateAdr page", () => {
  beforeEach(() => {
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    localStorage.setItem("hammurabi.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("starts with the name and the problem; the rest of the content is one click away", async () => {
    serve();
    const user = userEvent.setup();
    renderCreate();
    expect(headingOutline()).toEqual([
      [2, "Raise an ADR"],
      [3, "DACI"],
    ]);
    expect(screen.getByRole("textbox", { name: /^Problem/ })).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /^Context/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Context, decision, alternatives and consequences" }));
    for (const field of [/^Context/, /^Decision/, /^Alternatives considered/, /^Consequences/]) {
      expect(screen.getByRole("textbox", { name: field })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "Back to the ADRs" })).toHaveAttribute("href", "/adrs");
  });

  test("raises an ADR with the problem alone: no name is sent, and it lands on the new ADR", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderCreate();
    await user.type(screen.getByRole("textbox", { name: /^Problem/ }), "Which queue do we use?");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("at /adrs/12")).toBeInTheDocument();
    expect(stub.find("POST", "/api/v1/adrs")?.body).toEqual({
      problem: "Which queue do we use?",
      context: "",
      decision: "",
      alternatives: "",
      consequences: "",
      participants: [],
    });
  });

  test("a name and an Approver are sent; the caller is never offered as an extra participant", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderCreate();
    await user.type(screen.getByRole("textbox", { name: /^Name/ }), " Pick a queue ");
    await user.type(screen.getByRole("textbox", { name: /^Problem/ }), "Which queue?");
    await user.type(screen.getByRole("combobox", { name: "Approver" }), "al");
    // The signed-in user (id 5, Dana) is the Driver already: not offered.
    expect(await screen.findByRole("option", { name: "Alex Approver (alex@example.test)" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Dana Driver (dana@example.test)" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Alex Approver (alex@example.test)" }));
    await user.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() => expect(stub.find("POST", "/api/v1/adrs")).toBeDefined());
    expect(stub.find("POST", "/api/v1/adrs")?.body).toMatchObject({ name: "Pick a queue", participants: [{ userId: 6, role: "APPROVER" }] });
  });

  test("an empty problem is refused before any request", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderCreate();
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("Describe the problem or question")).toBeInTheDocument();
    expect(stub.find("POST", "/api/v1/adrs")).toBeUndefined();
  });

  test("a taken name is a field error and nothing is lost", async () => {
    serve(() => problem(409));
    const user = userEvent.setup();
    renderCreate();
    await user.type(screen.getByRole("textbox", { name: /^Name/ }), "Pick a queue");
    await user.type(screen.getByRole("textbox", { name: /^Problem/ }), "Which queue?");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("An ADR with this name already exists.")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /^Problem/ })).toHaveValue("Which queue?");
    expect(screen.getByRole("button", { name: "Create" })).toBeEnabled();
  });

  test("any other failure is an inline alert in fixed words", async () => {
    serve(() => problem(400));
    const user = userEvent.setup();
    renderCreate();
    await user.type(screen.getByRole("textbox", { name: /^Problem/ }), "Which queue?");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Check the fields: the problem is required");
  });

  test("Cancel returns to the list", async () => {
    serve();
    const user = userEvent.setup();
    renderCreate();
    await user.click(screen.getByRole("link", { name: "Cancel" }));
    expect(await screen.findByText("at /adrs")).toBeInTheDocument();
  });
});
