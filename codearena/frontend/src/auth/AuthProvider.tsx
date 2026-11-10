import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, refreshAccessToken, setAccessToken, setOnAuthLost } from "../api/client";
import type { Role } from "../api/types";

export interface User {
  id: string;
  name: string;
  roles: Role[];
}

interface AuthState {
  user: User | null;
  ready: boolean;
  isAdmin: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState>(null!);

/** Reads display data from the token. The server never trusts this; it verifies the signature. */
function userFromToken(token: string): User {
  const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
  return { id: payload.sub, name: payload.name, roles: payload.roles ?? [] };
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  const apply = useCallback((token: string | null) => {
    setAccessToken(token);
    setUser(token ? userFromToken(token) : null);
  }, []);

  useEffect(() => {
    setOnAuthLost(() => apply(null));
    // A refresh cookie from an earlier visit logs the user straight in.
    refreshAccessToken().then((t) => apply(t)).finally(() => setReady(true));
  }, [apply]);

  // Renew the access token shortly before it expires (5 min for admins, 15 for users).
  useEffect(() => {
    if (!user) return;
    const every = user.roles.includes("ADMIN") ? 4 * 60_000 : 13 * 60_000;
    const id = setInterval(() => refreshAccessToken().then(apply), every);
    return () => clearInterval(id);
  }, [user, apply]);

  const login = useCallback(async (email: string, password: string) => {
    const t = await api<{ accessToken: string }>("/auth/login", { method: "POST", body: { email, password } });
    apply(t.accessToken);
  }, [apply]);

  const register = useCallback(async (email: string, password: string, displayName: string) => {
    await api("/auth/register", { method: "POST", body: { email, password, displayName } });
    await login(email, password);
  }, [login]);

  const logout = useCallback(async () => {
    try {
      await api("/auth/logout", { method: "POST" });
    } finally {
      apply(null);
    }
  }, [apply]);

  const value = useMemo(
    () => ({ user, ready, isAdmin: !!user?.roles.includes("ADMIN"), login, register, logout }),
    [user, ready, login, register, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);