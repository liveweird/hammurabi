import type { AdrParticipant, AdrParticipantRequest } from "../api/adrs";
import type { PickedOption } from "../components/SearchPicker";

/** The DACI set while it is being edited: the single seats hold one pick (or none), the multi seats a list. Picks are user-id + label. */
export type ParticipantsState = {
  driver: PickedOption | null;
  approver: PickedOption | null;
  contributors: PickedOption[];
  informed: PickedOption[];
};

export const EMPTY_PARTICIPANTS: ParticipantsState = { driver: null, approver: null, contributors: [], informed: [] };

const pickOf = (participant: AdrParticipant): PickedOption => ({ value: String(participant.userId), label: participant.name });

/** The edit state of an ADR's current participants (the label is the recorded name; the directory adds the email to NEW picks). */
export function participantsState(participants: readonly AdrParticipant[]): ParticipantsState {
  const of = (role: AdrParticipant["role"]) => participants.filter((participant) => participant.role === role).map(pickOf);
  return { driver: of("DRIVER")[0] ?? null, approver: of("APPROVER")[0] ?? null, contributors: of("CONTRIBUTOR"), informed: of("INFORMED") };
}

/** The wire set for a participants PUT; the Driver seat must be filled (the form checks, the server re-judges). */
export function participantsRequest(state: ParticipantsState): AdrParticipantRequest[] {
  const seat = (pick: PickedOption | null, role: AdrParticipantRequest["role"]) =>
    pick === null ? [] : [{ userId: Number(pick.value), role }];
  const many = (picks: PickedOption[], role: AdrParticipantRequest["role"]) => picks.map((pick) => ({ userId: Number(pick.value), role }));
  return [
    ...seat(state.driver, "DRIVER"),
    ...seat(state.approver, "APPROVER"),
    ...many(state.contributors, "CONTRIBUTOR"),
    ...many(state.informed, "INFORMED"),
  ];
}

/** Every user id already holding a seat — the pickers leave them out, since a person has one role per ADR. */
export function takenIds(state: ParticipantsState): Set<string> {
  return new Set([state.driver, state.approver, ...state.contributors, ...state.informed].flatMap((pick) => (pick === null ? [] : [pick.value])));
}
