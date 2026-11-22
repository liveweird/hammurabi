import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import App from "../App";
import appSource from "../App.tsx?raw";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

// The heading-outline guard for EVERY page the router registers (web/CLAUDE.md "One heading outline
// everywhere"): the PageHeader title is the h2, sections h3, inner blocks h4. Rendered through the
// real <App/> routes under an ADMIN session with an empty-everything API, so it pins the structure
// every page keeps whatever it loads (the per-page tests pin the loaded outlines).

/** Route patterns → a concrete path the page renders at. */
function concretePath(pattern: string): string {
  return pattern.replace(":id", "1");
}

const ROUTE_PATTERNS = [...appSource.matchAll(/<Route\s+(?:index|path="([^"]+)")/g)]
  .map((m) => m[1] ?? "")
  .filter((p) => p !== "*");

// The tab title each route settles on (WCAG 2.4.2: "{page} · Hammurabi"). The detail routes answer
// 404 here, so they show their load-state title (the area name), not a record's.
const TAB_TITLES: Record<string, string> = {
  "/": "My work · Hammurabi",
  "/my-work": "My work · Hammurabi",
  "/login": "Sign in · Hammurabi",
  "/reset-password": "Reset password · Hammurabi",
  "/reset-password/confirm": "Choose a new password · Hammurabi",
  "/decision-log": "Decision log · Hammurabi",
  "/adrs": "ADRs · Hammurabi",
  "/adrs/new": "Raise an ADR · Hammurabi",
  "/adrs/:id": "ADRs · Hammurabi",
  "/teams": "Teams · Hammurabi",
  "/teams/:id": "Teams · Hammurabi",
  "/tags": "Tags · Hammurabi",
  "/users": "Users · Hammurabi",
  "/users/new": "New user · Hammurabi",
  "/users/:id/edit": "Edit user · Hammurabi",
  "/users/:id/features": "Feature flags · Hammurabi",
  "/feature-flags": "Feature flags · Hammurabi",
  "/change-password": "Change password · Hammurabi",
  "/notification-preferences": "Notification preferences · Hammurabi",
  "/changelog": "Changelog · Hammurabi",
};

/** The route as App.tsx spells it, rooted: the index route is "/". */
function routeKey(pattern: string): string {
  return pattern === "" ? "/" : `/${pattern.replace(/^\//, "")}`;
}

// Pages outside the shell (the auth cards) render signed OUT; the rest need a session.
const SIGNED_OUT = new Set(["/login", "/reset-password", "/reset-password/confirm"]);

function emptyApi() {
  return vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    if (/\/api\/v1\/(users|teams)(\?|$)/.test(url)) {
      return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
}

describe("heading outline of every registered page", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", emptyApi());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  test("the route list was read from App.tsx", () => {
    expect(ROUTE_PATTERNS.length).toBeGreaterThanOrEqual(12);
  });

  test("every route has an expected tab title, and none is stale", () => {
    expect(Object.keys(TAB_TITLES).sort()).toEqual(ROUTE_PATTERNS.map(routeKey).sort());
  });

  test.each(ROUTE_PATTERNS.map((p) => [routeKey(p)] as const))(
    "%s starts at h2 and never skips a level",
    async (pattern) => {
      if (!SIGNED_OUT.has(pattern)) {
        localStorage.setItem("hammurabi.auth.token", "fake-token");
        localStorage.setItem("hammurabi.auth.roles", JSON.stringify(["ADMIN"]));
        localStorage.setItem("hammurabi.auth.userId", "1");
      }
      renderWithProviders(<App />, { route: concretePath(pattern) });
      // Settled: a heading exists and nothing is still loading, so late sections are in the outline too.
      await waitFor(() => {
        expect(screen.getAllByRole("heading").length).toBeGreaterThan(0);
        expect(document.querySelector('[aria-busy="true"], .mantine-Loader-root')).toBeNull();
      });
      expect(document.title).toBe(TAB_TITLES[pattern]);
      const outline = headingOutline();
      expect(outline[0][0], JSON.stringify(outline)).toBe(2);
      outline.forEach(([level], i) => {
        if (i > 0) expect(level, `${outline[i][1]} in ${JSON.stringify(outline)}`).toBeLessThanOrEqual(outline[i - 1][0] + 1);
      });
    },
  );
});
