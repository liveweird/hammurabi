import { describe, expect, test } from "vitest";
import { activeAdrFilterCount, parseAdrListParams, writeAdrListParams } from "./adrListParams";

const parse = (query: string) => parseAdrListParams(new URLSearchParams(query));

describe("ADR list params", () => {
  test("a bare URL is the unfiltered list", () => {
    expect(parse("")).toEqual({ q: "", status: [], mine: false, role: null });
  });

  test("reads q, repeated status, mine and role", () => {
    expect(parse("q=db&status=ACTIVE&status=IN_REVIEW&mine=1&role=APPROVER")).toEqual({
      q: "db",
      status: ["ACTIVE", "IN_REVIEW"],
      mine: true,
      role: "APPROVER",
    });
  });

  test("ignores unknown, repeated and misplaced values", () => {
    expect(parse("status=NOPE&status=ACTIVE&status=ACTIVE&mine=yes&role=DRIVER")).toEqual({ q: "", status: ["ACTIVE"], mine: false, role: null });
    expect(parse("mine=1&role=OWNER").role).toBeNull();
  });

  test("writing lays a patch over the filters and keeps unrelated keys", () => {
    const next = writeAdrListParams(new URLSearchParams("foo=bar&status=ACTIVE"), { q: "db", mine: true, role: "DRIVER" });
    expect(next.toString()).toBe("foo=bar&q=db&status=ACTIVE&mine=1&role=DRIVER");
  });

  test("defaults leave no key behind, and a role without mine is dropped", () => {
    const base = new URLSearchParams("q=db&status=ACTIVE&mine=1&role=DRIVER");
    expect(writeAdrListParams(base, { q: "", status: [], mine: false }).toString()).toBe("");
    expect(writeAdrListParams(new URLSearchParams(), { role: "DRIVER" }).toString()).toBe("");
  });

  test("counts the active panel filters", () => {
    expect(activeAdrFilterCount(parse(""))).toBe(0);
    expect(activeAdrFilterCount(parse("q=%20&status=ACTIVE&mine=1&role=DRIVER"))).toBe(2);
    expect(activeAdrFilterCount(parse("q=db&status=ACTIVE&mine=1"))).toBe(3);
  });
});
