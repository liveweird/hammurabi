import type { TFunction } from "i18next";
import type { AdrCreateBody, AdrResponse, AdrUpdateBody } from "../api/adrs";
import { participantsRequest, type ParticipantsState } from "./adrParticipants";
import { nameRule } from "./formRules";

// Server limits (adrs/Adr.kt) mirrored client-side.
export const MAX_ADR_NAME_LENGTH = 120;
export const MAX_ADR_TEXT_LENGTH = 20_000;

/** The Markdown fields in reading order; `problem` is the one that is always required. */
export const ADR_TEXT_FIELDS = ["problem", "context", "decision", "alternatives", "consequences"] as const;
export type AdrTextField = (typeof ADR_TEXT_FIELDS)[number];

export type AdrFormValues = { name: string } & Record<AdrTextField, string>;

export const EMPTY_ADR_FORM: AdrFormValues = { name: "", problem: "", context: "", decision: "", alternatives: "", consequences: "" };

export function adrFormValues(adr: AdrResponse): AdrFormValues {
  return {
    name: adr.name,
    problem: adr.problem,
    context: adr.context,
    decision: adr.decision,
    alternatives: adr.alternatives,
    consequences: adr.consequences,
  };
}

/** Mirrors the server: a PUT needs a name (1..120), a create may leave it blank (it defaults to the problem's first line); the problem is always required. */
export function adrFormValidation(t: TFunction, { nameRequired }: { nameRequired: boolean }) {
  const tooLong = (value: string) => (value.length > MAX_ADR_TEXT_LENGTH ? t("adrs.validation.textLength", { max: MAX_ADR_TEXT_LENGTH }) : null);
  const optionalName = (value: string) => (value.trim().length <= MAX_ADR_NAME_LENGTH ? null : t("adrs.validation.nameLength"));
  return {
    name: nameRequired ? nameRule(t, "adrs.validation.nameLength", MAX_ADR_NAME_LENGTH) : optionalName,
    problem: (value: string) => (value.trim() === "" ? t("adrs.validation.problemRequired") : tooLong(value)),
    context: tooLong,
    decision: tooLong,
    alternatives: tooLong,
    consequences: tooLong,
  };
}

/** The create body: a blank name is left out (the server names the ADR after the problem); the caller is the Driver, so `extra` holds no Driver. */
export function toCreateBody(values: AdrFormValues, extra: ParticipantsState): AdrCreateBody {
  const name = values.name.trim();
  return {
    ...(name === "" ? {} : { name }),
    problem: values.problem,
    context: values.context,
    decision: values.decision,
    alternatives: values.alternatives,
    consequences: values.consequences,
    participants: participantsRequest(extra),
  };
}

/** The whole document for a PUT (a full replace): the form's content and the expiry date to keep or set (`null` = none). */
export function toUpdateBody(values: AdrFormValues, expiresOn: string | null): AdrUpdateBody {
  return { ...values, name: values.name.trim(), expiresOn };
}
