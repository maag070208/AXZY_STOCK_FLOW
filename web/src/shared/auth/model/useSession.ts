import { useSyncExternalStore } from "react";
import { sessionStore, type SessionState } from "@shared/auth/model/sessionStore";

/** Estado de sesión reactivo. Se suscribe al store externo (no hay contexto que pueda desincronizarse). */
export const useSession = (): SessionState =>
  useSyncExternalStore(sessionStore.subscribe, sessionStore.getState, sessionStore.getState);
