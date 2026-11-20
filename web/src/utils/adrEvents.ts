import type { TFunction } from "i18next";
import type { AdrEventResponse } from "../api/adrs";
import { isAdrRole, isAdrStatus } from "./adrStatus";
import { ADR_TEXT_FIELDS } from "./adrForm";

// The history renders the server's structured events in the viewer's language (`adr-model.md` "History"): the event type picks the title,
// its string params fill the sentence and the changed-field lines. No rendered text is stored, so a Polish reader gets Polish for old events too.

const DASH = "—";

/** A status name from an event param in the viewer's language (an unknown value shows as recorded). */
function statusName(value: string | undefined, t: TFunction): string {
  return isAdrStatus(value) ? t(`adrs.status.${value}`) : (value ?? DASH);
}

function roleName(value: string | undefined, t: TFunction): string {
  return isAdrRole(value) ? t(`adrs.role.${value}`) : (value ?? DASH);
}

/** The one sentence an event leads with. */
export function describeAdrEvent(event: AdrEventResponse, t: TFunction): string {
  const p = event.params;
  switch (event.type) {
    case "CREATED":
      return t("adrs.history.event.CREATED");
    case "UPDATED":
      return t("adrs.history.event.UPDATED");
    case "TRANSITIONED":
      return t("adrs.history.event.TRANSITIONED", { from: statusName(p.from, t), to: statusName(p.to, t) });
    case "PARTICIPANT_ADDED":
      return t("adrs.history.event.PARTICIPANT_ADDED", { name: p.name ?? DASH, role: roleName(p.role, t) });
    case "PARTICIPANT_REMOVED":
      return t("adrs.history.event.PARTICIPANT_REMOVED", { name: p.name ?? DASH, role: roleName(p.role, t) });
    case "PARTICIPANT_CHANGED":
      return t("adrs.history.event.PARTICIPANT_CHANGED", { name: p.name ?? DASH, from: roleName(p.from, t), to: roleName(p.to, t) });
    case "EXPIRED":
      return t("adrs.history.event.EXPIRED", { expiresOn: p.expiresOn ?? DASH });
    case "SUPERSEDED":
      return t("adrs.history.event.SUPERSEDED", { key: p.key ?? DASH });
    case "TAGS_CHANGED":
      return t("adrs.history.event.TAGS_CHANGED");
    case "SUCCESSOR_UNLINKED":
      // Written on the predecessor too when its owner detached the pending successor: that one names the successor, not a predecessor.
      return t(p.successorId === undefined ? "adrs.history.event.SUCCESSOR_UNLINKED" : "adrs.history.event.SUCCESSOR_DETACHED", { key: p.key ?? DASH });
    case "SUCCESSOR_LINKED":
    case "SUCCESSOR_ACTIVATED":
      return t(`adrs.history.event.${event.type}`, { key: p.key ?? DASH });
    case "COMMENTED":
    case "QUESTION_ASKED":
    case "QUESTION_ANSWERED":
    case "QUESTION_REOPENED":
    case "ISSUE_RAISED":
    case "ESCALATED":
      return t(`adrs.history.event.${event.type}`);
    case "ISSUE_RESOLVED":
      return t(p.kind === "ESCALATION" ? "adrs.history.event.ESCALATION_RESOLVED" : "adrs.history.event.ISSUE_RESOLVED");
    case "ISSUE_REOPENED":
      return t(p.kind === "ESCALATION" ? "adrs.history.event.ESCALATION_REOPENED" : "adrs.history.event.ISSUE_REOPENED");
  }
}

/** One line per changed field of an UPDATED event (the Markdown fields by name only, the short ones with their sides). */
function changedLines(p: AdrEventResponse["params"], t: TFunction): string[] {
  return (p.changed ?? "")
    .split(",")
    .filter((field) => field !== "")
    .map((field) => {
      if (field === "name") return t("adrs.history.line.name", { from: p["name.from"] ?? DASH, to: p["name.to"] ?? DASH });
      if (field === "expiresOn") {
        const from = p["expiresOn.from"];
        const to = p["expiresOn.to"];
        if (from !== undefined && to !== undefined) return t("adrs.history.line.expiryChanged", { from, to });
        return to !== undefined ? t("adrs.history.line.expirySet", { to }) : t("adrs.history.line.expiryCleared", { from: from ?? DASH });
      }
      const known = (ADR_TEXT_FIELDS as readonly string[]).includes(field);
      return t("adrs.history.line.textChanged", { field: known ? t(`adrs.field.${field as (typeof ADR_TEXT_FIELDS)[number]}`) : field });
    });
}

/** The tag names a TAGS_CHANGED event added and removed, one line each (a side with none has no line). */
function tagLines(p: AdrEventResponse["params"], t: TFunction): string[] {
  return [
    ...(p.added ? [t("adrs.history.line.tagsAdded", { tags: p.added.split(",").join(", ") })] : []),
    ...(p.removed ? [t("adrs.history.line.tagsRemoved", { tags: p.removed.split(",").join(", ") })] : []),
  ];
}

/** The dimmed detail lines under an event's sentence: the new ADR's key and name, an edit's changed fields, a tag change's names, or the other ADR's name for a link. */
export function adrEventLines(event: AdrEventResponse, t: TFunction): string[] {
  const p = event.params;
  switch (event.type) {
    case "CREATED":
      return [t("adrs.history.line.created", { key: p.key ?? DASH, name: p.name ?? DASH })];
    case "SUPERSEDED":
    case "SUCCESSOR_LINKED":
    case "SUCCESSOR_UNLINKED":
    case "SUCCESSOR_ACTIVATED":
      return [t("adrs.history.line.adrName", { name: p.name ?? DASH })];
    case "UPDATED":
      return changedLines(p, t);
    case "TAGS_CHANGED":
      return tagLines(p, t);
    default:
      return [];
  }
}

/** Who did it: the recorded name (with a note for a since-deleted account), or "the system" for the sweep. */
export function adrEventActor(event: AdrEventResponse, t: TFunction): string {
  if (event.userId === null || event.userName === null) return t("adrs.history.system");
  return event.userDeleted ? t("adrs.history.userDeleted", { name: event.userName }) : event.userName;
}
