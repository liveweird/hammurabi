import type { DiscussionEntry, DiscussionPage } from "../api/adrDiscussion";
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
    tags: [],
    supersedes: null,
    supersededBy: null,
    allowedTransitions: ["SOLUTION_DRAFT", "IN_REVIEW", "CANCELLED"],
    canEdit: true,
    canManageParticipants: true,
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
