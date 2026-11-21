import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import type { NotificationItem } from "../api/notifications";
import { describeNotification } from "./notificationText";

const t = i18n.t.bind(i18n);

const notice = (over: Partial<NotificationItem> = {}): NotificationItem => ({
  id: 1,
  type: "STATUS_CHANGED",
  seen: false,
  createdAt: 0,
  actorId: 7,
  actorName: "Ada",
  adrId: 42,
  adrKey: "ADR-0042",
  adrName: "Use Postgres",
  otherAdrId: null,
  otherAdrKey: null,
  detail: null,
  ...over,
});

describe("describeNotification", () => {
  test.each<[Partial<NotificationItem>, string]>([
    [{ type: "ROLE_ASSIGNED", detail: "DRIVER" }, "Ada gave you a role on ADR-0042: Driver"],
    [{ type: "ROLE_REMOVED", detail: "INFORMED" }, "Ada took your role on ADR-0042 away: Informed"],
    [{ type: "STATUS_CHANGED", detail: "IN_REVIEW" }, "Ada changed the status of ADR-0042 to In review"],
    [{ type: "DISCUSSION_POSTED", detail: "ESCALATION" }, "Ada posted on ADR-0042: Escalation"],
    [{ type: "DISCUSSION_SETTLED", detail: "QUESTION" }, "Ada marked your question on ADR-0042 as answered"],
    [{ type: "DISCUSSION_SETTLED", detail: "ISSUE" }, "Ada resolved your entry on ADR-0042: Issue"],
    [{ type: "DISCUSSION_SETTLED", detail: "ESCALATION" }, "Ada resolved your entry on ADR-0042: Escalation"],
    [{ type: "ADR_EXPIRED", detail: "2026-11-30", actorId: null, actorName: null }, "ADR-0042 expired: its expiry date 2026-11-30 has passed"],
    [{ type: "ADR_SUPERSEDED", otherAdrId: 43, otherAdrKey: "ADR-0043" }, "ADR-0042 was superseded by ADR-0043"],
    [{ type: "SUCCESSOR_LINKED", otherAdrId: 43, otherAdrKey: "ADR-0043" }, "Ada set ADR-0043 to replace ADR-0042"],
    [{ type: "SUCCESSOR_UNLINKED", otherAdrId: 43, otherAdrKey: "ADR-0043" }, "Ada detached ADR-0043: it no longer replaces ADR-0042"],
    [{ type: "EXPIRY_REMINDER", detail: "2026-12-01", actorId: null, actorName: null }, "ADR-0042 expires on 2026-12-01"],
  ])("%j reads as one sentence", (over, sentence) => {
    expect(describeNotification(notice(over), t)).toBe(sentence);
  });

  test("a notice with no actor is the system's", () => {
    expect(describeNotification(notice({ type: "ROLE_ASSIGNED", detail: "APPROVER", actorId: null, actorName: null }), t)).toBe("The system gave you a role on ADR-0042: Approver");
  });

  test("a detail this build does not know shows as sent, a missing one as a dash", () => {
    expect(describeNotification(notice({ type: "STATUS_CHANGED", detail: "PARKED" }), t)).toBe("Ada changed the status of ADR-0042 to PARKED");
    expect(describeNotification(notice({ type: "ROLE_ASSIGNED", detail: "OWNER" }), t)).toContain(": OWNER");
    expect(describeNotification(notice({ type: "DISCUSSION_POSTED", detail: "POLL" }), t)).toContain(": POLL");
    expect(describeNotification(notice({ type: "DISCUSSION_SETTLED", detail: null }), t)).toBe("Ada resolved your entry on ADR-0042: —");
    expect(describeNotification(notice({ type: "STATUS_CHANGED", detail: null }), t)).toContain("to —");
    expect(describeNotification(notice({ type: "SUCCESSOR_LINKED", otherAdrKey: null }), t)).toBe("Ada set — to replace ADR-0042");
  });

  test("a type this build does not know shows its raw name", () => {
    expect(describeNotification(notice({ type: "FROM_THE_FUTURE" as NotificationItem["type"] }), t)).toBe("FROM_THE_FUTURE");
  });

  test("the words follow the viewer's language and are the ADR pages' own", async () => {
    await i18n.changeLanguage("pl");
    try {
      expect(describeNotification(notice({ type: "ROLE_ASSIGNED", detail: "DRIVER" }), i18n.t.bind(i18n))).toBe("Ada przypisał/a Ci rolę w ADR-0042: Prowadzący/a");
      expect(describeNotification(notice({ type: "STATUS_CHANGED", detail: "ACTIVE" }), i18n.t.bind(i18n))).toBe("Ada zmienił/a status ADR-0042 na „Obowiązujący”");
    } finally {
      await i18n.changeLanguage("en");
    }
  });
});
