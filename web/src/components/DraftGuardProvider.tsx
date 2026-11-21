import { useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { UNSAFE_DataRouterContext, useBlocker, type BlockerFunction } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { getToken } from "../api/session";
import { useBeforeUnloadGuard } from "../hooks/useBeforeUnloadGuard";
import { DraftGuardContext } from "../hooks/useDraftGuard";
import ConfirmActionModal from "./ConfirmActionModal";

type DiscardDialogProps = {
  discard: (() => void) | null;
  keep: () => void;
};

function DiscardDialog({ discard, keep }: DiscardDialogProps) {
  const { t } = useTranslation();
  return (
    <ConfirmActionModal
      opened={discard !== null}
      onClose={keep}
      title={t("common.discard.title")}
      message={t("common.discard.message")}
      cancelLabel={t("common.discard.keep")}
      confirmLabel={t("common.discard.confirm")}
      onConfirm={() => discard?.()}
    />
  );
}

/** Lettuce's route guard at shell scope so multiple mounted ADR drafts share one blocker. */
function RoutedDiscardDialog({ hasDrafts, ...local }: DiscardDialogProps & { hasDrafts: () => boolean }) {
  const shouldBlock: BlockerFunction = ({ currentLocation, nextLocation }) =>
    currentLocation.pathname !== nextLocation.pathname &&
    (nextLocation.pathname !== "/login" || getToken() !== null) &&
    hasDrafts();
  const blocker = useBlocker(shouldBlock);
  if (blocker.state === "blocked") {
    return <DiscardDialog discard={() => {
      local.keep();
      blocker.proceed();
    }} keep={blocker.reset} />;
  }
  return <DiscardDialog {...local} />;
}

export default function DraftGuardProvider({ children }: { children: ReactNode }) {
  const [drafts] = useState(() => new Map<symbol, symbol | undefined>());
  const [dirty, setDirty] = useState(false);
  const [pending, setPending] = useState<(() => void) | null>(null);
  const inDataRouter = useContext(UNSAFE_DataRouterContext) !== null;
  useBeforeUnloadGuard(dirty);

  const register = useCallback((group: symbol | undefined) => {
    const key = Symbol();
    drafts.set(key, group);
    if (drafts.size === 1) setDirty(true);
    return () => {
      if (drafts.delete(key) && drafts.size === 0) setDirty(false);
    };
  }, [drafts]);
  const hasDrafts = useCallback((group?: symbol) =>
    group === undefined ? drafts.size > 0 : [...drafts.values()].includes(group), [drafts]);
  const confirm = useCallback((discard: () => void) => setPending(() => discard), []);
  const guard = useMemo(() => ({ register, hasDrafts, confirm }), [register, hasDrafts, confirm]);
  const keep = () => setPending(null);
  const discard = pending && (() => {
    keep();
    pending();
  });

  return (
    <DraftGuardContext.Provider value={guard}>
      {children}
      {inDataRouter
        ? <RoutedDiscardDialog discard={discard} keep={keep} hasDrafts={hasDrafts} />
        : <DiscardDialog discard={discard} keep={keep} />}
    </DraftGuardContext.Provider>
  );
}
