import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { jsonResponse } from "../test/http";
import { stubFetch } from "../test/fetchStub";
import { renderWithProviders, screen, within } from "../test/render";
import type { PickedOption } from "./SearchPicker";
import UserMultiPicker from "./UserMultiPicker";

const DIRECTORY = {
  items: [
    { id: 7, name: "Mia Manager", email: "mia@example.test" },
    { id: 12, name: "Mia Other", email: "other@example.test" },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};

function Harness({ initial = [], exclude = new Set<string>() }: { initial?: PickedOption[]; exclude?: Set<string> }) {
  const [picks, setPicks] = useState<PickedOption[]>(initial);
  return <UserMultiPicker label="Contributors" picks={picks} onChange={setPicks} exclude={exclude} />;
}

describe("UserMultiPicker", () => {
  beforeEach(() => {
    stubFetch(() => jsonResponse(200, DIRECTORY));
    localStorage.setItem("hammurabi.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("each pick joins the list, the box empties, and the picked person is no longer offered", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const box = screen.getByRole("combobox", { name: "Contributors" });
    await user.type(box, "mi");
    await user.click(await screen.findByRole("option", { name: "Mia Manager (mia@example.test)" }));
    const list = screen.getByRole("list", { name: "Contributors to add" });
    expect(within(list).getByText("Mia Manager (mia@example.test)")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Contributors" })).toHaveValue("");

    await user.type(screen.getByRole("combobox", { name: "Contributors" }), "mi");
    expect(await screen.findByRole("option", { name: "Mia Other (other@example.test)" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Mia Manager (mia@example.test)" })).not.toBeInTheDocument();
  });

  test("a pick can be removed; people reserved by the other seats are never offered", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={[{ value: "7", label: "Mia Manager" }]} exclude={new Set(["12"])} />);
    await user.click(screen.getByRole("button", { name: "Remove Mia Manager" }));
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
    await user.type(screen.getByRole("combobox", { name: "Contributors" }), "mi");
    expect(await screen.findByRole("option", { name: "Mia Manager (mia@example.test)" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Mia Other (other@example.test)" })).not.toBeInTheDocument();
  });

  test("clearing the box without a choice adds nobody", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const box = screen.getByRole("combobox", { name: "Contributors" });
    await user.type(box, "mi");
    await screen.findByRole("option", { name: "Mia Manager (mia@example.test)" });
    await user.clear(box);
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });
});
