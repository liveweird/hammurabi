import type { TFunction } from "i18next";
import type { NotificationItem } from "../api/notifications";
import { isDiscussionKind } from "./adrDiscussion";
import { isAdrRole, isAdrStatus } from "./adrStatus";

// The bell renders the server's structured notices in the viewer's language (`adr-model.md` "Notifications"): the type picks the sentence, the
// notice's ids and `detail` fill it. The role, status and entry-kind WORDS are the ADR pages' own (`adrs.role.*`, `adrs.status.*`,
// `adrs.discussion.kind.*`), never copied here. A value this build does not know shows as the server sent it (the contract: a client treats an
// unknown value as plain text), and a missing one as a dash.

const DASH = "—";

function roleWord(detail: string | null, t: TFunction): string {
  return isAdrRole(detail) ? t(`adrs.role.${detail}`) : (detail ?? DASH);
}

function statusWord(detail: string | null, t: TFunction): string {
  return isAdrStatus(detail) ? t(`adrs.status.${detail}`) : (detail ?? DASH);
}

function kindWord(detail: string | null, t: TFunction): string {
  return isDiscussionKind(detail) ? t(`adrs.discussion.kind.${detail}`) : (detail ?? DASH);
}

/** One sentence per notice, text only (`escapeValue` is off in i18n because React escapes). An unknown type shows its raw name. */
export function describeNotification(n: NotificationItem, t: TFunction): string {
  const params = { actor: n.actorName ?? t("notifications.system"), key: n.adrKey, other: n.otherAdrKey ?? DASH, date: n.detail ?? DASH };
  switch (n.type) {
    case "ROLE_ASSIGNED":
    case "ROLE_REMOVED":
      return t(`notifications.event.${n.type}`, { ...params, role: roleWord(n.detail, t) });
    case "STATUS_CHANGED":
      // An in-review ADR returned to draft is "sent back" (the server's own detail word), not a plain move to the draft status.
      return n.detail === "SENT_BACK"
        ? t("notifications.event.STATUS_SENT_BACK", params)
        : t("notifications.event.STATUS_CHANGED", { ...params, status: statusWord(n.detail, t) });
    case "DISCUSSION_POSTED":
      return t("notifications.event.DISCUSSION_POSTED", { ...params, kind: kindWord(n.detail, t) });
    case "DISCUSSION_SETTLED":
      // An answered QUESTION is its own sentence; an issue or escalation (and whatever a newer server adds) was resolved.
      return n.detail === "QUESTION"
        ? t("notifications.event.DISCUSSION_ANSWERED", params)
        : t("notifications.event.DISCUSSION_RESOLVED", { ...params, kind: kindWord(n.detail, t) });
    case "ADR_EXPIRED":
    case "ADR_SUPERSEDED":
    case "SUCCESSOR_LINKED":
    case "SUCCESSOR_UNLINKED":
    case "SUCCESSOR_ACCEPTED":
    case "EXPIRY_REMINDER":
      return t(`notifications.event.${n.type}`, params);
    case "EXPIRY_CHANGED":
      // The server's detail is the new date; absent means the date was removed (its own sentence).
      return n.detail === null
        ? t("notifications.event.EXPIRY_REMOVED", params)
        : t("notifications.event.EXPIRY_CHANGED", params);
    default:
      return n.type;
  }
}
