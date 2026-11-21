import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { isLastAdminConflict, loadErrorMessage, saveErrorMessage } from "./saveError";

const t = i18n.t;

const KEYS = {
  forbidden: "users.lastAdminError",
  notFound: "users.userGone",
  conflict: "users.emailAlreadyInUse",
  invalid: "common.error.actionFailed",
  failedStatus: "common.error.createFailedStatus",
  failed: "common.error.createFailedNetwork",
} as const;

const timeout = new DOMException("timed out", "TimeoutError");

describe("saveErrorMessage", () => {
  test("maps each status to its key and falls back per class", () => {
    expect(saveErrorMessage(new ApiError(403, null), t, KEYS)).toBe(t(KEYS.forbidden));
    expect(saveErrorMessage(new ApiError(404, null), t, KEYS)).toBe(t(KEYS.notFound));
    expect(saveErrorMessage(new ApiError(409, null), t, KEYS)).toBe(t(KEYS.conflict));
    expect(saveErrorMessage(new ApiError(400, null), t, KEYS)).toBe(t(KEYS.invalid));
    expect(saveErrorMessage(new ApiError(500, null), t, KEYS)).toBe("Create failed (500)");
    expect(saveErrorMessage(timeout, t, KEYS)).toBe(t("common.error.timeout"));
    expect(saveErrorMessage(new Error("net"), t, KEYS)).toBe(t(KEYS.failed));
  });

  test("an unmapped status routes through the generic fallbacks when keys are omitted", () => {
    const minimal = { failed: KEYS.failed } as const;
    expect(saveErrorMessage(new ApiError(404, null), t, minimal)).toBe(t(KEYS.failed));
  });
});

describe("loadErrorMessage", () => {
  test("tags ApiErrors with the status and falls back per class", () => {
    expect(loadErrorMessage(new ApiError(502, null), t)).toBe("Load failed (502)");
    expect(loadErrorMessage(new ApiError(429, null), t)).toBe("Too many requests. Wait a minute and try again.");
    expect(loadErrorMessage(timeout, t)).toBe(t("common.error.timeout"));
    expect(loadErrorMessage(new Error("net"), t)).toBe(t("common.error.network"));
  });

  test("the search's two typed 429s say what to do, any other 429 stays the rate-limit message", () => {
    const typed = (type: string) => new ApiError(429, { type, title: "x", status: 429 });
    expect(loadErrorMessage(typed("urn:hammurabi:problem:search-timeout"), t)).toBe(
      "The search took too long. Narrow it - add a word or a filter - and try again.",
    );
    expect(loadErrorMessage(typed("urn:hammurabi:problem:search-busy"), t)).toBe("Search is busy right now. Try again in a moment.");
    expect(loadErrorMessage(typed("about:blank"), t)).toBe("Too many requests. Wait a minute and try again.");
  });
});

describe("isLastAdminConflict", () => {
  const conflict = (body: object) => new ApiError(409, { title: "Conflict", status: 409, ...body });

  test("tells the last-administrator 409 by its problem type", () => {
    expect(isLastAdminConflict(conflict({ type: "urn:hammurabi:problem:last-administrator", detail: "x" }))).toBe(true);
  });

  test("never by the detail text: an untyped 409 that mentions an administrator is not it", () => {
    expect(isLastAdminConflict(conflict({ detail: "The last administrator cannot be demoted" }))).toBe(false);
    expect(isLastAdminConflict(conflict({ type: "about:blank", detail: "The last administrator cannot be demoted" }))).toBe(false);
  });

  test("only a 409, only an ApiError", () => {
    expect(isLastAdminConflict(new ApiError(400, { type: "urn:hammurabi:problem:last-administrator", title: "x", status: 400 }))).toBe(false);
    expect(isLastAdminConflict(new Error("net"))).toBe(false);
  });
});
