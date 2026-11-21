import { describe, expect, test } from "vitest";
import { ApiError } from "../api/http";
import i18n from "../i18n";
import { adrConflict, adrWriteError } from "./adrErrors";

const t = i18n.t.bind(i18n);
const problem = (status: number, type?: string) => new ApiError(status, type ? { type, status } : { status });

describe("ADR write errors", () => {
  test("the typed 409s are told apart by their problem type", () => {
    expect(adrConflict(problem(409, "urn:hammurabi:problem:discussion-closed"))).toBe("discussionClosed");
    expect(adrConflict(problem(409, "urn:hammurabi:problem:revision-conflict"))).toBe("revision");
    expect(adrConflict(problem(409, "urn:hammurabi:problem:adr-frozen"))).toBe("frozen");
    expect(adrConflict(problem(409, "urn:hammurabi:problem:illegal-transition"))).toBe("illegalTransition");
    expect(adrConflict(problem(409, "urn:hammurabi:problem:successor-not-accepted"))).toBe("successorNotAccepted");
  });

  test("an unaccepted successor and the accept's own refusals have their words", () => {
    expect(adrWriteError(problem(409, "urn:hammurabi:problem:successor-not-accepted"), t, "transition")).toMatch(/has to accept it first/);
    expect(adrWriteError(problem(404), t, "accept")).toMatch(/not pending any more/);
    expect(adrWriteError(problem(409), t, "accept")).toMatch(/not in force yet/);
    expect(adrWriteError(problem(403), t, "accept")).toBe("Your role on this ADR does not allow this.");
  });

  test("an untyped 409, another status or a non-API error is not a typed conflict", () => {
    expect(adrConflict(problem(409))).toBeNull();
    expect(adrConflict(problem(409, "about:blank"))).toBeNull();
    expect(adrConflict(problem(400, "urn:hammurabi:problem:adr-frozen"))).toBeNull();
    expect(adrConflict(new Error("x"))).toBeNull();
  });

  test("each typed conflict has its own words", () => {
    expect(adrWriteError(problem(409, "urn:hammurabi:problem:revision-conflict"), t, "save")).toMatch(/Someone else changed this ADR/);
    expect(adrWriteError(problem(409, "urn:hammurabi:problem:adr-frozen"), t, "save")).toMatch(/frozen/);
    expect(adrWriteError(problem(409, "urn:hammurabi:problem:illegal-transition"), t, "transition")).toMatch(/cannot move there/);
  });

  test("a discussion write has its own words: the closed window, the vanished entry and what to check", () => {
    expect(adrWriteError(problem(409, "urn:hammurabi:problem:discussion-closed"), t, "discussion")).toMatch(/status no longer takes this/);
    expect(adrWriteError(problem(404), t, "discussion")).toBe("This entry or its ADR no longer exists.");
    expect(adrWriteError(problem(400), t, "discussion")).toMatch(/at most 10,000 characters/);
  });

  test("a plain 409 is a name clash - or, for the successor link, a loop or a rival; 403 and 404 have their own words", () => {
    expect(adrWriteError(problem(409), t, "create")).toBe("An ADR with this name already exists.");
    expect(adrWriteError(problem(409), t, "supersedes")).toMatch(/close a loop of successors/);
    expect(adrWriteError(problem(403), t, "save")).toBe("Your role on this ADR does not allow this.");
    expect(adrWriteError(problem(404), t, "save")).toBe("This ADR no longer exists.");
  });

  test("a 400 says what to check for the write that failed", () => {
    expect(adrWriteError(problem(400), t, "create")).toMatch(/problem is required/);
    expect(adrWriteError(problem(400), t, "save")).toMatch(/expiry date must not be in the past/);
    expect(adrWriteError(problem(400), t, "transition")).toMatch(/entering review or activation needs/);
    expect(adrWriteError(problem(400), t, "participants")).toMatch(/exactly one Driver/);
    expect(adrWriteError(problem(400), t, "tags")).toMatch(/only tags from the registry/);
    expect(adrWriteError(problem(400), t, "supersedes")).toMatch(/pick another ADR/);
  });

  test("anything else is the status or the network, never the server's text", () => {
    expect(adrWriteError(problem(500), t, "save")).toBe("Save failed (500)");
    expect(adrWriteError(new TypeError("Failed to fetch"), t, "save")).toBe("Save failed. Check your connection and try again.");
  });
});
