import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { adrFixture } from "../test/adrFixtures";
import { noContent, problem, stubFetch } from "../test/fetchStub";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import AdrParticipantsPanel from "./AdrParticipantsPanel";

const PEOPLE = [
  { userId: 5, name: "Dana Driver", role: "DRIVER" as const },
  { userId: 6, name: "Alex Approver", role: "APPROVER" as const },
  { userId: 7, name: "Cora Contributor", role: "CONTRIBUTOR" as const },
];
const DIRECTORY = {
  items: [
    { id: 8, name: "Ida Informed", email: "ida@example.test" },
    { id: 9, name: "Dave Driver", email: "dave@example.test" },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};

function serve(participantsAnswer: () => Response = noContent) {
  return stubFetch((r) => {
    if (r.method === "PUT") return participantsAnswer();
    if (r.url.startsWith("/api/v1/users/directory")) return jsonResponse(200, DIRECTORY);
    return jsonResponse(200, { items: [] });
  });
}

describe("AdrParticipantsPanel", () => {
  beforeEach(() => {
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    localStorage.setItem("hammurabi.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("lists the participants by role, marks the caller and says when a seat is empty", () => {
    renderWithProviders(<AdrParticipantsPanel adr={adrFixture({ participants: PEOPLE })} />);
    expect(screen.getByText("Dana Driver")).toBeInTheDocument();
    expect(screen.getByText("You")).toBeInTheDocument();
    expect(screen.getByText("Alex Approver")).toBeInTheDocument();
    expect(screen.getByText("Cora Contributor")).toBeInTheDocument();
    // Informed is empty.
    expect(screen.getByText("Nobody")).toBeInTheDocument();
  });

  test("an unassigned Approver reads as such", () => {
    renderWithProviders(<AdrParticipantsPanel adr={adrFixture()} />);
    expect(screen.getByText("Not assigned")).toBeInTheDocument();
    expect(screen.getAllByText("Nobody")).toHaveLength(2);
  });

  test("whoever may not manage the participants has no Edit button", () => {
    renderWithProviders(<AdrParticipantsPanel adr={adrFixture({ participants: PEOPLE, canManageParticipants: false })} />);
    expect(screen.queryByRole("button", { name: "Edit participants" })).not.toBeInTheDocument();
  });

  test("a refetch that advances the revision while the editor is open does not turn the save into a lost update", async () => {
    const stub = serve();
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<AdrParticipantsPanel adr={adrFixture({ participants: PEOPLE, revision: 4 })} />);
    await user.click(screen.getByRole("button", { name: "Edit participants" }));
    rerender(<AdrParticipantsPanel adr={adrFixture({ participants: PEOPLE, revision: 8 })} />);
    await user.click(screen.getByRole("button", { name: "Save participants" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1/participants")).toBeDefined());
    expect(stub.find("PUT", "/api/v1/adrs/1/participants")?.headers.get("X-Expected-Revision")).toBe("4");
  });

  test("the editor replaces the whole set under the held revision, hands over nothing else and closes", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<AdrParticipantsPanel adr={adrFixture({ participants: PEOPLE, revision: 4 })} />);
    await user.click(screen.getByRole("button", { name: "Edit participants" }));
    await user.type(screen.getByRole("combobox", { name: "Informed" }), "id");
    await user.click(await screen.findByRole("option", { name: "Ida Informed (ida@example.test)" }));
    await user.click(screen.getByRole("button", { name: "Save participants" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Save participants" })).not.toBeInTheDocument());
    const put = stub.find("PUT", "/api/v1/adrs/1/participants");
    expect(put?.headers.get("X-Expected-Revision")).toBe("4");
    expect(put?.body).toEqual({
      participants: [
        { userId: 5, role: "DRIVER" },
        { userId: 6, role: "APPROVER" },
        { userId: 7, role: "CONTRIBUTOR" },
        { userId: 8, role: "INFORMED" },
      ],
    });
  });

  test("the Driver can be handed over but never left empty", async () => {
    const stub = serve();
    const user = userEvent.setup();
    renderWithProviders(<AdrParticipantsPanel adr={adrFixture({ participants: PEOPLE })} />);
    await user.click(screen.getByRole("button", { name: "Edit participants" }));
    // The Driver seat is the first picker; the Approver's clear button follows it (Mantine hides the X from the accessibility tree).
    await user.click(screen.getAllByLabelText("Clear the user")[0]);
    await user.click(screen.getByRole("button", { name: "Save participants" }));
    expect(await screen.findByText("An ADR needs a Driver")).toBeInTheDocument();
    expect(stub.find("PUT", "/api/v1/adrs/1/participants")).toBeUndefined();

    await user.type(screen.getByRole("combobox", { name: "Driver" }), "da");
    await user.click(await screen.findByRole("option", { name: "Dave Driver (dave@example.test)" }));
    expect(screen.queryByText("An ADR needs a Driver")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save participants" }));
    await waitFor(() => expect(stub.find("PUT", "/api/v1/adrs/1/participants")?.body).toMatchObject({ participants: [{ userId: 9, role: "DRIVER" }, { userId: 6, role: "APPROVER" }, { userId: 7, role: "CONTRIBUTOR" }] }));
  });

  test("a stale revision is the reload notice; reloading leaves the editor", async () => {
    serve(() => problem(409, "urn:hammurabi:problem:revision-conflict"));
    const user = userEvent.setup();
    renderWithProviders(<AdrParticipantsPanel adr={adrFixture({ participants: PEOPLE })} />);
    await user.click(screen.getByRole("button", { name: "Edit participants" }));
    await user.click(screen.getByRole("button", { name: "Save participants" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Someone else changed this ADR");
    await user.click(screen.getByRole("button", { name: "Reload the ADR" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit participants" })).toBeInTheDocument());
  });

  test("other failures are fixed words, and Cancel leaves the editor without a request", async () => {
    const stub = serve(() => problem(400));
    const user = userEvent.setup();
    renderWithProviders(<AdrParticipantsPanel adr={adrFixture({ participants: PEOPLE })} />);
    await user.click(screen.getByRole("button", { name: "Edit participants" }));
    await user.click(screen.getByRole("button", { name: "Save participants" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Check the participants");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Edit participants" })).toBeInTheDocument();
    expect(stub.count("PUT", "/api/v1/adrs/1/participants")).toBe(1);
    expect(within(document.body).queryByRole("alert")).not.toBeInTheDocument();
  });
});
