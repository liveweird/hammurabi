import type { ParseKeys } from "i18next";
import { IconGavel, IconHistory, IconBell, IconHome2, IconKey, IconScale, IconTags, IconToggleLeft, IconUsers, IconUsersGroup, type Icon } from "@tabler/icons-react";
import { adrsPath, decisionLogPath } from "./adrLinks";
import { notificationPreferencesPath } from "./notificationLinks";
import { tagsPath } from "./tagLinks";
import { teamsPath } from "./teamLinks";
import { usersPath } from "./userLinks";

export type NavLeaf = {
  to: string;
  /** An i18n key, resolved with t() at render time. */
  label: ParseKeys;
  icon: Icon;
  /** When set, the leaf renders only for ADMIN sessions. */
  adminOnly?: boolean;
  /**
   * Further routes this leaf stays highlighted on (a report group's other tabs, each of which
   * is its own route). Prefix-matched like `to`; the leaf itself always links to `to`.
   */
  activeFor?: ReadonlyArray<string>;
  /** Extra palette search terms (raw, language-neutral) — a page renamed since people learned its old name. */
  keywords?: ReadonlyArray<string>;
};

/** A labelled, always-open block of leaves — a section, never a collapsible group. */
export type NavSection = {
  label: ParseKeys;
  items: ReadonlyArray<NavLeaf>;
};

/** The personal work view is the default destination after sign-in. */
export const myWorkPath = "/my-work";

/**
 * The navigation model, shared by the sidebar and the command palette. Sections are labelled,
 * always-open blocks (never collapsible groups): every leaf is always in the DOM, so tests and
 * deep links address the links directly, and a static label costs less vertical space than a
 * toggle. My work, the Decision log and ADRs are visible to every signed-in user. The Administration section holds Teams (everyone reads
 * the flat teams list; only its create/edit/delete are ADMIN) alongside the ADMIN-only Tags (the tag registry), Users and
 * Feature flags leaves — a non-admin session sees just Teams there.
 */
const NAV_SECTIONS: ReadonlyArray<NavSection> = [
  {
    label: "appShell.section.overview",
    items: [
      { to: myWorkPath, label: "appShell.nav.myWork", icon: IconHome2, activeFor: ["/"], keywords: ["home", "strona główna"] },
      { to: decisionLogPath, label: "appShell.nav.decisionLog", icon: IconScale, keywords: ["overview", "dashboard", "in force", "expiring", "awaiting", "adr"] },
      { to: adrsPath, label: "appShell.nav.adrs", icon: IconGavel, keywords: ["decision", "architecture", "record", "adr"] },
    ],
  },
  {
    label: "appShell.section.administration",
    items: [
      { to: teamsPath, label: "appShell.nav.teams", icon: IconUsersGroup },
      { to: tagsPath, label: "appShell.nav.tags", icon: IconTags, adminOnly: true, keywords: ["category", "label", "adr"] },
      { to: usersPath, label: "appShell.nav.users", icon: IconUsers, adminOnly: true },
      { to: "/feature-flags", label: "appShell.nav.featureFlags", icon: IconToggleLeft, adminOnly: true },
    ],
  },
];

/** Account-scoped leaves: the header user menu and the palette render them, the sidebar never. */
export const ACCOUNT_NAV: ReadonlyArray<NavLeaf> = [
  { to: "/change-password", label: "appShell.nav.changePassword", icon: IconKey },
  { to: notificationPreferencesPath, label: "appShell.nav.notificationPreferences", icon: IconBell },
  { to: "/changelog", label: "appShell.nav.changelog", icon: IconHistory },
];

/** The sections a session may see: admin-only leaves filtered, empty sections dropped. */
export function visibleSections(admin: boolean): NavSection[] {
  return NAV_SECTIONS.flatMap((section) => {
    const items = section.items.filter((leaf) => !leaf.adminOnly || admin);
    return items.length > 0 ? [{ ...section, items }] : [];
  });
}

/**
 * Longest-matching-prefix active-link resolution — "/" only matches exactly. A leaf matches on its
 * own `to` and on every `activeFor` route; the answer is always the leaf's `to`.
 */
export function activeNavPath(pathname: string, leaves: ReadonlyArray<NavLeaf>): string | null {
  const matches = (path: string) =>
    path === "/" ? pathname === "/" : pathname === path || pathname.startsWith(`${path}/`);
  return (
    leaves
      .flatMap((leaf) => [leaf.to, ...(leaf.activeFor ?? [])].filter(matches).map((path) => ({ to: leaf.to, length: path.length })))
      .sort((a, b) => b.length - a.length)[0]?.to ?? null
  );
}
