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
      return t("adrs.history.event.SUPERSEDED");
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

/** The dimmed detail lines under an event's sentence: the new ADR's key and name, or an edit's changed fields. */
export function adrEventLines(event: AdrEventResponse, t: TFunction): string[] {
  if (event.type === "CREATED") return [t("adrs.history.line.created", { key: event.params.key ?? DASH, name: event.params.name ?? DASH })];
  if (event.type === "UPDATED") return changedLines(event.params, t);
  return [];
}

/** Who did it: the recorded name (with a note for a since-deleted account), or "the system" for the sweep. */
export function adrEventActor(event: AdrEventResponse, t: TFunction): string {
  if (event.userId === null || event.userName === null) return t("adrs.history.system");
  return event.userDeleted ? t("adrs.history.userDeleted", { name: event.userName }) : event.userName;
}
