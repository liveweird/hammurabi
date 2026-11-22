import { describe, expect, test } from "vitest";
import { ACCOUNT_NAV, activeNavPath, visibleSections } from "./navigation";

describe("visibleSections", () => {
  test("a regular session sees My work, Decision log, ADRs and Teams", () => {
    const sections = visibleSections(false);
    expect(sections.map((s) => s.label)).toEqual(["appShell.section.overview", "appShell.section.administration"]);
    const paths = sections.flatMap((s) => s.items.map((l) => l.to));
    expect(paths).toEqual(["/my-work", "/decision-log", "/adrs", "/teams"]);
  });

  test("an admin session gets Teams, Tags, Users and Feature flags in the Administration section", () => {
    const admin = visibleSections(true).find((s) => s.label === "appShell.section.administration");
    expect(admin?.items.map((l) => l.to)).toEqual(["/teams", "/tags", "/users", "/feature-flags"]);
  });

  test("the account leaves never sit in a section", () => {
    const sectionPaths = visibleSections(true).flatMap((s) => s.items.map((l) => l.to));
    for (const leaf of ACCOUNT_NAV) expect(sectionPaths).not.toContain(leaf.to);
    expect(ACCOUNT_NAV.map((l) => l.to)).toEqual(["/change-password", "/notification-preferences", "/changelog"]);
  });
});

describe("activeNavPath", () => {
  const leaves = visibleSections(true).flatMap((s) => s.items);

  test("resolves the longest matching prefix", () => {
    expect(activeNavPath("/users/new", leaves)).toBe("/users");
    expect(activeNavPath("/users/3/edit", leaves)).toBe("/users");
    expect(activeNavPath("/feature-flags", leaves)).toBe("/feature-flags");
    expect(activeNavPath("/teams/3", leaves)).toBe("/teams");
    expect(activeNavPath("/tags", leaves)).toBe("/tags");
    expect(activeNavPath("/decision-log", leaves)).toBe("/decision-log");
    expect(activeNavPath("/adrs", leaves)).toBe("/adrs");
    expect(activeNavPath("/adrs/new", leaves)).toBe("/adrs");
    expect(activeNavPath("/adrs/12", leaves)).toBe("/adrs");
  });

  test("a leaf stays highlighted on every route it lists in activeFor, and still links to its own", () => {
    const custom = [
      { to: "/a", label: "appShell.nav.myWork", icon: leaves[0].icon, activeFor: ["/b/deep"] },
      { to: "/b", label: "appShell.nav.myWork", icon: leaves[0].icon },
    ] as const;
    // The longest match still wins across leaves.
    expect(activeNavPath("/b/deep/x", custom)).toBe("/a");
    expect(activeNavPath("/b/other", custom)).toBe("/b");
  });

  test("My work is active at its canonical path and legacy root", () => {
    expect(activeNavPath("/my-work", leaves)).toBe("/my-work");
    expect(activeNavPath("/", leaves)).toBe("/my-work");
    expect(activeNavPath("/nowhere", leaves)).toBeNull();
  });
});
