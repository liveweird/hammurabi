/* eslint-disable react-refresh/only-export-components */
// -- the auth store helpers (signIn/signOut/useAuthed) live beside the route guards on purpose; a mixed file opts out of fast-refresh, which is fine for this rarely-edited module
import { useSyncExternalStore, type ReactElement } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { getToken, isAdmin, ROLES_KEY, TOKEN_KEY } from "./api/session";
import { myWorkPath } from "./utils/navigation";

const listeners = new Set<() => void>();

function subscribe(cb: () => void): () => void {
  const onStorage = (e: StorageEvent) => {
    if (e.key === TOKEN_KEY || e.key === ROLES_KEY || e.key === null) cb();
  };
  window.addEventListener("storage", onStorage);
  listeners.add(cb);
  return () => {
    window.removeEventListener("storage", onStorage);
    listeners.delete(cb);
  };
}

export function notifyAuthChange(): void {
  listeners.forEach((cb) => cb());
}

/** Why the login page opens with a notice: the session ended (sign-out, rejected refresh) or the user changed their password. */
export type SignedOutReason = "sessionEnded" | "passwordChanged";

let pendingSignedOutReason: SignedOutReason | null = null;

export function flagSignedOut(reason: SignedOutReason = "sessionEnded"): void {
  pendingSignedOutReason = reason;
}

/** The pending reason without consuming it (the login page reads it during render). */
export function pendingSignedOut(): SignedOutReason | null {
  return pendingSignedOutReason;
}

export function consumeSignedOut(): boolean {
  const v = pendingSignedOutReason !== null;
  pendingSignedOutReason = null;
  return v;
}

function useAuth(): { token: string | null; isAuthenticated: boolean } {
  const token = useSyncExternalStore(subscribe, getToken, () => null);
  return { token, isAuthenticated: token !== null };
}

/** Subscribe to role updates published by silent refresh and other browser tabs. */
export function useAdmin(): boolean {
  return useSyncExternalStore(subscribe, isAdmin, () => false);
}

export type SignInLocationState = { from?: { pathname?: string; search?: string; hash?: string } } | null;

export function signInDestination(state: SignInLocationState): string {
  const from = state?.from;
  if (!from?.pathname) return myWorkPath;
  const pathname = from.pathname === "/" ? myWorkPath : from.pathname;
  return `${pathname}${from.search ?? ""}${from.hash ?? ""}`;
}

export function RequireAuth(): ReactElement {
  const { isAuthenticated } = useAuth();
  const location = useLocation();
  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  return <Outlet />;
}

/**
 * The ADMIN-only route element (wraps the user-management routes in App.tsx): a regular user is sent
 * to My work before the guarded page mounts. The server's `requireAdmin` is the rule; the pages'
 * queries additionally stay `enabled: isAdmin()` so no request fires for a redirected caller.
 */
export function RequireAdmin(): ReactElement {
  if (!useAdmin()) return <Navigate to={myWorkPath} replace />;
  return <Outlet />;
}

export function RedirectIfAuthed({ children }: { children: ReactElement }): ReactElement {
  const { isAuthenticated } = useAuth();
  const location = useLocation();
  if (isAuthenticated) {
    return <Navigate to={signInDestination(location.state as SignInLocationState)} replace />;
  }
  return children;
}
