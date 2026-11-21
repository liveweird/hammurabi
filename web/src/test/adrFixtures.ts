import type { DiscussionEntry, DiscussionPage } from "../api/adrDiscussion";
import type { AdrEventResponse, AdrLogItem, AdrOverview, AdrPage, AdrResponse } from "../api/adrs";

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
    tags: [],
    supersedes: null,
    supersededBy: null,
    allowedTransitions: ["SOLUTION_DRAFT", "IN_REVIEW", "CANCELLED"],
    canEdit: true,
    canManageParticipants: true,
    canChangeApprover: true,
    canEditTags: true,
    canEditSuccessor: true,
    canDetachSuccessor: false,
    canComment: true,
    canRaiseIssue: false,
    openIssueCount: 0,
    unansweredQuestionCount: 0,
    ...overrides,
  };
}

export function adrPageFixture(items: AdrPage["items"] = []): AdrPage {
  return { items, page: 1, pageSize: 20, total: items.length };
}

export function eventFixture(overrides: Partial<AdrEventResponse> = {}): AdrEventResponse {
  return { id: 1, userId: 5, userName: "Dana Driver", userDeleted: false, type: "CREATED", params: {}, createdAt: Date.UTC(2026, 10, 1, 9, 30), ...overrides };
}

/** A discussion entry as `GET /adrs/{id}/discussion` serves it (a live open question by Cora, no verdicts), with `overrides` laid over it. */
export function entryFixture(overrides: Partial<DiscussionEntry> = {}): DiscussionEntry {
  return {
    id: 11,
    adrId: 1,
    kind: "QUESTION",
    status: "OPEN",
    body: "Why not MySQL?",
    deleted: false,
    author: { id: 7, name: "Cora Contributor", deleted: false },
    createdAt: Date.UTC(2026, 10, 3, 9, 5),
    editedAt: null,
    resolution: null,
    stateChangedBy: null,
    stateChangedAt: null,
    canEdit: false,
    canDelete: false,
    canChangeState: false,
    ...overrides,
  };
}

export function discussionPageFixture(items: DiscussionEntry[] = [], total = items.length): DiscussionPage {
  return { items, page: 1, pageSize: 20, total };
}

/** The decision log as `GET /adrs/overview` serves it: one tagged ADR in force, one in review that waits for the caller, one expiring. */
export function overviewFixture(overrides: Partial<AdrOverview> = {}): AdrOverview {
  const item = (id: number, name: string, extra: Partial<AdrLogItem> = {}): AdrLogItem => ({
    id,
    number: id,
    key: `ADR-${String(id).padStart(4, "0")}`,
    name,
    expiresOn: null,
    statusChangedAt: Date.UTC(2026, 10, 3, 9),
    ...extra,
  });
  const postgres = item(7, "Use PostgreSQL");
  return {
    generatedAt: Date.UTC(2026, 10, 21, 12),
    counts: [
      { status: "PROBLEM_RAISED", count: 1 },
      { status: "SOLUTION_DRAFT", count: 0 },
      { status: "IN_REVIEW", count: 1 },
      { status: "ACTIVE", count: 2 },
      { status: "CANCELLED", count: 0 },
      { status: "WITHDRAWN", count: 0 },
      { status: "EXPIRED", count: 0 },
      { status: "SUPERSEDED", count: 0 },
    ],
    inForce: {
      total: 2,
      categories: [{ id: 1, name: "Domain", tags: [{ name: "data", total: 3, items: [postgres], more: 2 }] }],
      hiddenTags: 0,
      untagged: { total: 1, items: [item(9, "Pick a queue")], more: 0 },
    },
    recentlyDecided: { total: 2, items: [postgres], more: 1 },
    expiringSoon: { days: 30, total: 1, items: [item(12, "Rotate the keys", { expiresOn: "2026-11-25" })], more: 0 },
    awaitingDecision: {
      total: 1,
      items: [{ ...item(5, "Adopt Kafka", { statusChangedAt: Date.UTC(2026, 10, 1) }), approver: { userId: 5, name: "Alex Approver", role: "APPROVER" }, mine: true }],
      more: 0,
      mineTotal: 1,
    },
    ...overrides,
  };
}
