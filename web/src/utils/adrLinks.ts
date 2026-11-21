/** The ONE place the ADR route family is spelled out — never hand-assemble an ADR URL. */
export const adrsPath = "/adrs";
export const newAdrPath = `${adrsPath}/new`;
export const adrPath = (id: number) => `${adrsPath}/${id}`;
/** Stable fragment for the validity controls on an ADR detail page. */
export const ADR_VALIDITY_ID = "validity";
export const adrValidityPath = (id: number) => `${adrPath(id)}#${ADR_VALIDITY_ID}`;
/** The list narrowed to the ADRs the signed-in user takes part in (see `utils/adrListParams.ts`). */
export const myAdrsPath = `${adrsPath}?mine=1`;
/** The decision log: the read-only overview of what is in force, what was decided lately, what expires and what waits for a decision. */
export const decisionLogPath = "/decision-log";
