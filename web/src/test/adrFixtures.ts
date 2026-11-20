import type { AdrEventResponse, AdrPage, AdrResponse } from "../api/adrs";

/** An ADR as `GET /adrs/{id}` serves it (a Driver's view of a fresh problem), with `overrides` laid over it. */
export function adrFixture(overrides: Partial<AdrResponse> = {}): AdrResponse {
  return {
    id: 1,
    number: 1,
    key: "ADR-0001",
    name: "Use PostgreSQL",
    problem: "We need a database.",
    context: "",
    decision: "",
    alternatives: "",
    consequences: "",
    status: "PROBLEM_RAISED",
    expiresOn: null,
    revision: 3,
    createdAt: Date.UTC(2026, 10, 1, 9, 30),
    createdBy: 5,
    updatedAt: Date.UTC(2026, 10, 2, 10, 15),
    statusChangedAt: Date.UTC(2026, 10, 1, 9, 30),
    participants: [{ userId: 5, name: "Dana Driver", role: "DRIVER" }],
    allowedTransitions: ["SOLUTION_DRAFT", "IN_REVIEW", "CANCELLED"],
    canEdit: true,
    canManageParticipants: true,
    ...overrides,
  };
}

export function adrPageFixture(items: AdrPage["items"] = []): AdrPage {
  return { items, page: 1, pageSize: 20, total: items.length };
}

export function eventFixture(overrides: Partial<AdrEventResponse> = {}): AdrEventResponse {
  return { id: 1, userId: 5, userName: "Dana Driver", userDeleted: false, type: "CREATED", params: {}, createdAt: Date.UTC(2026, 10, 1, 9, 30), ...overrides };
}
