import { describe, expect, test } from "vitest";
import { EMPTY_PARTICIPANTS, participantsRequest, participantsState, takenIds } from "./adrParticipants";

const PARTICIPANTS = [
  { userId: 5, name: "Dana Driver", role: "DRIVER" as const },
  { userId: 6, name: "Alex Approver", role: "APPROVER" as const },
  { userId: 7, name: "Cora Contributor", role: "CONTRIBUTOR" as const },
  { userId: 9, name: "Carl Contributor", role: "CONTRIBUTOR" as const },
  { userId: 8, name: "Ida Informed", role: "INFORMED" as const },
];

describe("ADR participants edit state", () => {
  test("the state is built from the participants, one seat per role", () => {
    const state = participantsState(PARTICIPANTS);
    expect(state.driver).toEqual({ value: "5", label: "Dana Driver" });
    expect(state.approver).toEqual({ value: "6", label: "Alex Approver" });
    expect(state.contributors.map((p) => p.value)).toEqual(["7", "9"]);
    expect(state.informed.map((p) => p.value)).toEqual(["8"]);
  });

  test("the request round-trips the set, with numeric user ids and the Driver first", () => {
    expect(participantsRequest(participantsState(PARTICIPANTS))).toEqual([
      { userId: 5, role: "DRIVER" },
      { userId: 6, role: "APPROVER" },
      { userId: 7, role: "CONTRIBUTOR" },
      { userId: 9, role: "CONTRIBUTOR" },
      { userId: 8, role: "INFORMED" },
    ]);
  });

  test("empty seats send nothing", () => {
    expect(participantsRequest(EMPTY_PARTICIPANTS)).toEqual([]);
    expect(participantsState([])).toEqual(EMPTY_PARTICIPANTS);
  });

  test("takenIds lists everyone holding a seat", () => {
    expect([...takenIds(participantsState(PARTICIPANTS))].sort()).toEqual(["5", "6", "7", "8", "9"]);
    expect(takenIds(EMPTY_PARTICIPANTS).size).toBe(0);
  });
});
