import { createContext, useContext, useEffect, useRef } from "react";

type DraftGuard = {
  register: (group: symbol | undefined) => () => void;
  hasDrafts: (group?: symbol) => boolean;
  confirm: (discard: () => void) => void;
};

export const DraftGuardContext = createContext<DraftGuard | null>(null);
export const DraftGroupContext = createContext<symbol | undefined>(undefined);

/** Registers only dirty forms. Call markSaved before a successful save navigates. */
export function useDraftGuard(dirty: boolean) {
  const guard = useContext(DraftGuardContext);
  const group = useContext(DraftGroupContext);
  const registration = useRef<(() => void) | undefined>(undefined);

  useEffect(() => {
    if (!dirty || !guard) return;
    const unregister = guard.register(group);
    registration.current = unregister;
    return () => {
      unregister();
      registration.current = undefined;
    };
  }, [dirty, group, guard]);

  return {
    markSaved: () => registration.current?.(),
    requestDiscard: (discard: () => void) => {
      if (dirty && guard) guard.confirm(discard);
      else discard();
    },
  };
}
