import { describe, expect, test } from "vitest";
import { activeAdrFilterCount, parseAdrListParams, writeAdrListParams } from "./adrListParams";

const parse = (query: string) => parseAdrListParams(new URLSearchParams(query));

describe("ADR list params", () => {
  test("a bare URL is the unfiltered list", () => {
    expect(parse("")).toEqual({ q: "", status: [], tag: [], mine: false, role: null });
  });

  test("reads q, repeated status, repeated tag, mine and role", () => {
    expect(parse("q=db&status=ACTIVE&status=IN_REVIEW&tag=data&tag=security&mine=1&role=APPROVER")).toEqual({
      q: "db",
      status: ["ACTIVE", "IN_REVIEW"],
      tag: ["data", "security"],
      mine: true,
      role: "APPROVER",
    });
  });

  test("ignores unknown, repeated and misplaced values", () => {
    expect(parse("status=NOPE&status=ACTIVE&status=ACTIVE&mine=yes&role=DRIVER")).toEqual({ q: "", status: ["ACTIVE"], tag: [], mine: false, role: null });
    expect(parse("tag=data&tag=data&tag=&tag=team").tag).toEqual(["data", "team"]);
    expect(parse("mine=1&role=OWNER").role).toBeNull();
  });

  test("writing lays a patch over the filters and keeps unrelated keys", () => {
    const next = writeAdrListParams(new URLSearchParams("foo=bar&status=ACTIVE&tag=data"), { q: "db", tag: ["data", "team"], mine: true, role: "DRIVER" });
    expect(next.toString()).toBe("foo=bar&q=db&status=ACTIVE&tag=data&tag=team&mine=1&role=DRIVER");
  });

  test("defaults leave no key behind, and a role without mine is dropped", () => {
    expect(writeAdrListParams(new URLSearchParams("q=db&status=ACTIVE&tag=data&mine=1&role=DRIVER"), { q: "", status: [], tag: [], mine: false }).toString()).toBe("");
    expect(writeAdrListParams(new URLSearchParams(), { role: "DRIVER" }).toString()).toBe("");
  });

  test("counts the active panel filters", () => {
    expect(activeAdrFilterCount(parse(""))).toBe(0);
    expect(activeAdrFilterCount(parse("q=%20&status=ACTIVE&mine=1&role=DRIVER"))).toBe(2);
    expect(activeAdrFilterCount(parse("q=db&status=ACTIVE&mine=1"))).toBe(3);
    expect(activeAdrFilterCount(parse("tag=data&tag=team"))).toBe(1);
  });
});
