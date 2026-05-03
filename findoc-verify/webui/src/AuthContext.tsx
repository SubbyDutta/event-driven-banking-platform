import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api, clearApiKey, getApiKey, setApiKey, type Me } from "./api";

type AuthState = {
  me: Me | null;
  loading: boolean;
  signIn: (key: string) => Promise<Me>;
  signOut: () => void;
};

const Ctx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const key = getApiKey();
    if (!key) { setLoading(false); return; }
    api.me().then(setMe).catch(() => clearApiKey()).finally(() => setLoading(false));
  }, []);

  async function signIn(key: string) {
    setApiKey(key);
    try {
      const m = await api.me();
      setMe(m);
      return m;
    } catch (e) {
      clearApiKey();
      throw e;
    }
  }

  function signOut() {
    clearApiKey();
    setMe(null);
  }

  return <Ctx.Provider value={{ me, loading, signIn, signOut }}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const v = useContext(Ctx);
  if (!v) throw new Error("useAuth must be inside AuthProvider");
  return v;
}
