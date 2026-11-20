/** The ONE place the ADR route family is spelled out — never hand-assemble an ADR URL. */
export const adrsPath = "/adrs";
export const newAdrPath = `${adrsPath}/new`;
export const adrPath = (id: number) => `${adrsPath}/${id}`;
/** The list narrowed to the ADRs the signed-in user takes part in (see `utils/adrListParams.ts`). */
export const myAdrsPath = `${adrsPath}?mine=1`;
