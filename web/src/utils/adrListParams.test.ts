import { describe, expect, test } from "vitest";
import { activeAdrFilterCount, hasAdrFilters, isInvertedRange, isUnsearchable, NO_ADR_FILTERS, parseAdrListParams, searchTermOf, writeAdrListParams } from "./adrListParams";

const parse = (query: string) => parseAdrListParams(new URLSearchParams(query));

describe("ADR list params", () => {
  test("a bare URL is the unfiltered list", () => {
    expect(parse("")).toEqual(NO_ADR_FILTERS);
  });

  test("reads q, repeated status, repeated tag, mine and role", () => {
    expect(parse("q=db&status=ACTIVE&status=IN_REVIEW&tag=data&tag=security&mine=1&participantRole=APPROVER")).toEqual({
      ...NO_ADR_FILTERS,
      q: "db",
      status: ["ACTIVE", "IN_REVIEW"],
      tag: ["data", "security"],
      mine: true,
      participantRole: "APPROVER",
    });
  });

  test("reads the full-text term, the dates and the yes/no filters under the list's own parameter names", () => {
    expect(
      parse(
        "text=message+queue&createdFrom=2026-01-01&createdTo=2026-02-01&updatedFrom=2026-03-01&updatedTo=2026-04-01&expiresBefore=2027-01-01&hasOpenIssues=true&hasPredecessor=false&hasSuccessor=true",
      ),
    ).toEqual({
      ...NO_ADR_FILTERS,
      text: "message queue",
      createdFrom: "2026-01-01",
      createdTo: "2026-02-01",
      updatedFrom: "2026-03-01",
      updatedTo: "2026-04-01",
      expiresBefore: "2027-01-01",
      hasOpenIssues: true,
      hasPredecessor: false,
      hasSuccessor: true,
    });
  });

  test("a participant (from a shared lens) is read, with its role; mine wins over it", () => {
    expect(parse("participantId=9&participantRole=DRIVER")).toMatchObject({ mine: false, participantId: 9, participantRole: "DRIVER" });
    expect(parse("participantId=9&mine=1&participantRole=DRIVER")).toMatchObject({ mine: true, participantId: null, participantRole: "DRIVER" });
    expect(parse("participantId=abc").participantId).toBeNull();
    expect(parse("participantId=0").participantId).toBeNull();
  });

  test("the four keys 0.9.x spelled differently are still read, the current spelling wins, and a write never emits the old ones", () => {
    expect(parse("participant=9&role=DRIVER&supersedes=true&supersededBy=false")).toMatchObject({
      participantId: 9,
      participantRole: "DRIVER",
      hasPredecessor: true,
      hasSuccessor: false,
    });
    expect(parse("participant=9&participantId=4&hasPredecessor=false&supersedes=true")).toMatchObject({ participantId: 4, hasPredecessor: false });
    // Any write of the filters re-spells them and drops the old keys; foreign keys stay.
    const written = writeAdrListParams(new URLSearchParams("foo=bar&participant=9&role=DRIVER&supersedes=true&supersededBy=false"), { q: "db" });
    expect(written.toString()).toBe("foo=bar&q=db&participantId=9&participantRole=DRIVER&hasPredecessor=true&hasSuccessor=false");
    expect(["participant", "role", "supersedes", "supersededBy"].some((key) => written.has(key))).toBe(false);
  });

  test("ignores unknown, repeated and misplaced values", () => {
    expect(parse("status=NOPE&status=ACTIVE&status=ACTIVE&mine=yes&participantRole=DRIVER")).toEqual({ ...NO_ADR_FILTERS, status: ["ACTIVE"] });
    expect(parse("tag=data&tag=data&tag=&tag=team").tag).toEqual(["data", "team"]);
    expect(parse("mine=1&participantRole=OWNER").participantRole).toBeNull();
    // Impossible dates and non-boolean words are no filter; a lone character is no search.
    expect(parse("createdFrom=2026-02-30&updatedTo=yesterday&hasOpenIssues=1&hasPredecessor=yes&text=a")).toEqual(NO_ADR_FILTERS);
  });

  test("the search term is trimmed and needs two characters", () => {
    expect(parse("text=%20%20ab%20").text).toBe("ab");
    expect(searchTermOf("  x ")).toBe("");
    expect(searchTermOf(null)).toBe("");
    expect(searchTermOf(" ok ")).toBe("ok");
  });

  test("the term is counted in code points and needs a letter or a digit, like the server", () => {
    expect(searchTermOf("!!")).toBe("");
    expect(searchTermOf(" -- ")).toBe("");
    expect(searchTermOf("\u00bd\u00bd")).toBe("");
    expect(searchTermOf("\u{1D56C}")).toBe("");
    expect(searchTermOf("\u{1F600}\u{1F600}")).toBe("");
    expect(searchTermOf("\u{1D56C}\u{1D56D}")).toBe("\u{1D56C}\u{1D56D}");
    expect(searchTermOf("a!")).toBe("a!");
    expect(searchTermOf("#7")).toBe("#7");
    expect(searchTermOf("\u017b\u00f3")).toBe("\u017b\u00f3");
    expect(parse("text=!!").text).toBe("");
    expect(writeAdrListParams(new URLSearchParams("text=ab"), { text: "--" }).toString()).toBe("");
  });

  test("a typed term the server would refuse is unsearchable, an empty box is not", () => {
    expect(isUnsearchable("")).toBe(false);
    expect(isUnsearchable("   ")).toBe(false);
    expect(isUnsearchable("a")).toBe(true);
    expect(isUnsearchable("!!")).toBe(true);
    expect(isUnsearchable(" ab ")).toBe(false);
  });

  test("writing lays a patch over the filters and keeps unrelated keys", () => {
    const next = writeAdrListParams(new URLSearchParams("foo=bar&status=ACTIVE&tag=data"), { q: "db", tag: ["data", "team"], mine: true, participantRole: "DRIVER" });
    expect(next.toString()).toBe("foo=bar&q=db&status=ACTIVE&tag=data&tag=team&mine=1&participantRole=DRIVER");
  });

  test("writing round-trips every filter", () => {
    const query = "q=db&text=queue&status=ACTIVE&tag=data&participantId=9&participantRole=INFORMED&createdFrom=2026-01-01&createdTo=2026-02-01&updatedFrom=2026-03-01&updatedTo=2026-04-01&expiresBefore=2027-01-01&hasOpenIssues=false&hasPredecessor=true&hasSuccessor=false";
    const params = parse(query);
    expect(writeAdrListParams(new URLSearchParams(), params).toString()).toBe(query);
  });

  test("defaults leave no key behind, and a role without mine or participant is dropped", () => {
    expect(writeAdrListParams(new URLSearchParams("q=db&text=ab&status=ACTIVE&tag=data&mine=1&participantRole=DRIVER&createdFrom=2026-01-01&hasOpenIssues=true"), NO_ADR_FILTERS).toString()).toBe("");
    expect(writeAdrListParams(new URLSearchParams(), { participantRole: "DRIVER" }).toString()).toBe("");
  });

  test("a patch can set one filter and clear another", () => {
    expect(writeAdrListParams(new URLSearchParams("text=ab&hasPredecessor=true"), { text: "", hasPredecessor: null, hasOpenIssues: true }).toString()).toBe("hasOpenIssues=true");
    expect(writeAdrListParams(new URLSearchParams(), { text: "a" }).toString()).toBe("");
    expect(writeAdrListParams(new URLSearchParams(), { createdFrom: "nope" }).toString()).toBe("");
  });

  test("counts the active panel filters (a range counts once, the search box not at all)", () => {
    expect(activeAdrFilterCount(parse(""))).toBe(0);
    expect(activeAdrFilterCount(parse("q=%20&status=ACTIVE&mine=1&participantRole=DRIVER"))).toBe(2);
    expect(activeAdrFilterCount(parse("q=db&status=ACTIVE&mine=1"))).toBe(3);
    expect(activeAdrFilterCount(parse("tag=data&tag=team"))).toBe(1);
    expect(activeAdrFilterCount(parse("createdFrom=2026-01-01&createdTo=2026-02-01&updatedTo=2026-04-01&expiresBefore=2027-01-01"))).toBe(3);
    expect(activeAdrFilterCount(parse("hasOpenIssues=false&hasPredecessor=true&hasSuccessor=false&participantId=4"))).toBe(4);
    expect(activeAdrFilterCount(parse("text=queue"))).toBe(0);
  });

  test("anything narrowing the list, the search term included, is a filter", () => {
    expect(hasAdrFilters(parse(""))).toBe(false);
    expect(hasAdrFilters(parse("text=queue"))).toBe(true);
    expect(hasAdrFilters(parse("hasPredecessor=false"))).toBe(true);
  });

  test("a range is inverted only when both ends are set and the start is later", () => {
    expect(isInvertedRange("2026-02-01", "2026-01-01")).toBe(true);
    expect(isInvertedRange("2026-01-01", "2026-01-01")).toBe(false);
    expect(isInvertedRange("", "2026-01-01")).toBe(false);
    expect(isInvertedRange("2026-02-01", "")).toBe(false);
  });
});
