import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import NotificationPreferences from "./NotificationPreferences";
import type { NotificationPreferences as Preferences } from "../api/notifications";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const PATH = "/api/v1/users/5/notification-preferences";
const TYPES = [
  "ROLE_ASSIGNED",
  "ROLE_REMOVED",
  "STATUS_CHANGED",
  "DISCUSSION_POSTED",
  "DISCUSSION_SETTLED",
  "ADR_EXPIRED",
  "ADR_SUPERSEDED",
  "SUCCESSOR_LINKED",
  "SUCCESSOR_UNLINKED",
  "EXPIRY_REMINDER",
  "EXPIRY_CHANGED",
  "SUCCESSOR_ACCEPTED",
  "PREDECESSOR_ENDED",
] as const;

/** Every kind on the bell and the email unless `off` names it: `"ROLE_REMOVED:email"` or `"ROLE_REMOVED:inApp"`. */
function prefs(off: string[] = [], emailAvailable = true): Preferences {
  return {
    emailAvailable,
    kinds: TYPES.map((type) => ({ type, inApp: !off.includes(`${type}:inApp`), email: !off.includes(`${type}:email`) })),
  };
}

type Kinds = Preferences["kinds"];

/** GET answers `stored`; a PUT answers `putStatus` and, on a 204, the next GET answers what the PUT sent (a kind it leaves out is on). */
function stubApi(stored: Preferences, putStatus = 204) {
  let current = stored;
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (url !== PATH) return Promise.resolve(jsonResponse(404, {}));
    if (init?.method === "PUT") {
      if (putStatus !== 204) return Promise.resolve(jsonResponse(putStatus, { title: "x", status: putStatus }));
      const sent = (JSON.parse(init.body as string) as { kinds: Kinds }).kinds;
      current = { ...current, kinds: current.kinds.map((k) => sent.find((s) => s.type === k.type) ?? { ...k, inApp: true, email: true }) };
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    return Promise.resolve(jsonResponse(200, current));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function putBodies(fetchMock: ReturnType<typeof stubApi>) {
  return fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT").map(([, init]) => JSON.parse(init?.body as string) as { kinds: Kinds });
}

const bell = (kind: string) => screen.getByRole("switch", { name: `Bell: ${kind}` });
const email = (kind: string) => screen.getByRole("switch", { name: `Email: ${kind}` });
const ASSIGNED = "You are given a role";
const REMOVED = "Your role is taken away";
const save = () => screen.getByRole("button", { name: "Save" });

describe("NotificationPreferences page", () => {
  beforeEach(() => {
    localStorage.setItem("hammurabi.auth.token", "fake-token");
    localStorage.setItem("hammurabi.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  test("shows a table with a row per kind and two switches with the stored state, under the h2 outline", async () => {
    stubApi(prefs(["ROLE_REMOVED:email", "STATUS_CHANGED:inApp"]));
    renderWithProviders(<NotificationPreferences />);
    await screen.findByRole("switch", { name: `Bell: ${ASSIGNED}` });
    const table = screen.getByRole("table", { name: "Notification preferences" });
    expect(within(table).getAllByRole("row")).toHaveLength(TYPES.length + 1);
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Notification", "Bell", "Email"]);
    expect(within(table).getAllByRole("switch")).toHaveLength(TYPES.length * 2);
    expect(bell(ASSIGNED)).toBeChecked();
    expect(email(ASSIGNED)).toBeChecked();
    expect(bell(REMOVED)).toBeChecked();
    expect(email(REMOVED)).not.toBeChecked();
    expect(screen.getByText("Someone gives you a DACI role on an ADR.")).toBeInTheDocument();
    // A row whose bell is off has its email off and disabled, whatever the email was stored as.
    expect(bell("An ADR changes status")).not.toBeChecked();
    expect(email("An ADR changes status")).not.toBeChecked();
    expect(email("An ADR changes status")).toBeDisabled();
    expect(email(ASSIGNED)).toBeEnabled();
    expect(headingOutline()).toEqual([[2, "Notification preferences"]]);
    expect(screen.queryByText(/Email is not set up/)).not.toBeInTheDocument();
    expect(save()).toBeDisabled();
  });

  test("a change saves every kind with its two switches, toasts and re-reads", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    const fetchMock = stubApi(prefs(["ADR_EXPIRED:email"]));
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: `Email: ${REMOVED}` }));
    await user.click(bell("An ADR is superseded"));
    await user.click(save());
    await waitFor(() => expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: "Notification preferences saved", color: "teal" })));
    // What the GET answered, edited: the same thirteen kinds, three of them switched off.
    expect(putBodies(fetchMock)).toEqual([{ kinds: prefs(["ROLE_REMOVED:email", "ADR_EXPIRED:email", "ADR_SUPERSEDED:inApp"]).kinds }]);
    await waitFor(() => expect(email(REMOVED)).not.toBeChecked());
    expect(bell("An ADR is superseded")).not.toBeChecked();
    expect(bell(ASSIGNED)).toBeChecked();
    expect(save()).toBeDisabled();
  });

  test("switching the bell off disables the email of the row, switching it back restores the email choice", async () => {
    stubApi(prefs());
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: `Bell: ${ASSIGNED}` }));
    expect(email(ASSIGNED)).toBeDisabled();
    expect(email(ASSIGNED)).not.toBeChecked();
    expect(save()).toBeEnabled();
    await user.click(bell(ASSIGNED));
    expect(email(ASSIGNED)).toBeEnabled();
    expect(email(ASSIGNED)).toBeChecked();
    // Back where it was loaded: nothing to save.
    expect(save()).toBeDisabled();
  });

  test("the email choice of a row whose bell is off is stored as it was", async () => {
    const fetchMock = stubApi(prefs(["ROLE_REMOVED:email"]));
    vi.spyOn(notifications, "show").mockReturnValue("id");
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: `Bell: ${REMOVED}` }));
    await user.click(bell(ASSIGNED));
    await user.click(save());
    await waitFor(() => expect(putBodies(fetchMock)).toHaveLength(1));
    expect(putBodies(fetchMock)[0].kinds).toEqual(prefs(["ROLE_ASSIGNED:inApp", "ROLE_REMOVED:inApp", "ROLE_REMOVED:email"]).kinds);
  });

  test("switching everything back on saves every kind on", async () => {
    vi.spyOn(notifications, "show").mockReturnValue("id");
    const fetchMock = stubApi(prefs(["ROLE_ASSIGNED:inApp", "ROLE_REMOVED:email"]));
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: `Bell: ${ASSIGNED}` }));
    await user.click(email(REMOVED));
    await user.click(save());
    await waitFor(() => expect(putBodies(fetchMock)).toEqual([{ kinds: prefs().kinds }]));
  });

  test("with no email on the deployment it says so and disables every email switch, the bell stays usable", async () => {
    stubApi(prefs([], false));
    renderWithProviders(<NotificationPreferences />);
    expect(await screen.findByText(/Email is not set up on this deployment/)).toBeInTheDocument();
    for (const type of ["You are given a role", "Your role is taken away", "An ADR expires"]) {
      expect(email(type)).toBeDisabled();
      expect(email(type)).toBeChecked();
      expect(bell(type)).toBeEnabled();
    }
  });

  test("a kind this build does not know is not shown, and its stored switches survive a save", async () => {
    vi.spyOn(notifications, "show").mockReturnValue("id");
    const stored = prefs();
    stored.kinds.push({ type: "FROM_THE_FUTURE" as Preferences["kinds"][number]["type"], inApp: true, email: false });
    const fetchMock = stubApi(stored);
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: `Email: ${ASSIGNED}` }));
    expect(screen.getAllByRole("switch")).toHaveLength(TYPES.length * 2);
    expect(screen.queryByText("FROM_THE_FUTURE")).not.toBeInTheDocument();
    await user.click(save());
    await waitFor(() => expect(putBodies(fetchMock)).toHaveLength(1));
    expect(putBodies(fetchMock)[0].kinds).toEqual([...prefs(["ROLE_ASSIGNED:email"]).kinds, { type: "FROM_THE_FUTURE", inApp: true, email: false }]);
  });

  test("a failed load shows an inline alert and no switches", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 }))));
    renderWithProviders(<NotificationPreferences />);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  test("a failed save shows the status inline, keeps the toggles and sends no toast", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    stubApi(prefs(), 500);
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: `Email: ${ASSIGNED}` }));
    await user.click(save());
    expect(await screen.findByText("Saving the preferences failed (500)")).toBeInTheDocument();
    expect(email(ASSIGNED)).not.toBeChecked();
    expect(toast).not.toHaveBeenCalled();
  });

  test("a network failure on save has its own words", async () => {
    const fetchMock = stubApi(prefs());
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: `Email: ${ASSIGNED}` }));
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === "PUT" ? Promise.reject(new TypeError("Failed to fetch")) : Promise.resolve(jsonResponse(200, prefs())),
    );
    await user.click(save());
    expect(await screen.findByText(/Saving the preferences failed\. Check your connection/)).toBeInTheDocument();
  });
});
