import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';

export interface AuthUser {
  id: string;
  login: string;
  name: string;
  avatarUrl: string;
  email: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  token: string | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  login: () => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const API_BASE = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1';
const AUTH_BASE = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1').replace('/api/v1', '/api/auth');

export const TOKEN_KEY = 'cg_jwt';

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser]       = useState<AuthUser | null>(null);
  const [token, setToken]     = useState<string | null>(() => localStorage.getItem(TOKEN_KEY));
  const [isLoading, setLoading] = useState(true);

  const logout = useCallback(() => {
    // Clear all auth-related storage first
    localStorage.removeItem(TOKEN_KEY);
    sessionStorage.clear();

    // Fire-and-forget: tell the backend (stateless, but good hygiene for future server-side revocation)
    const t = token;
    if (t) {
      fetch(`${AUTH_BASE}/logout`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' },
      }).catch(() => {/* ignore — logout is client-side regardless */});
    }

    setToken(null);
    setUser(null);
    // Hard redirect clears all in-memory React state (AuthProvider remounts clean)
    globalThis.location.replace('/login');
  }, [token]);

  // On mount (or token change), verify the token by calling /auth/me
  useEffect(() => {
    if (!token) {
      setLoading(false);
      return;
    }
    setLoading(true);
    fetch(`${AUTH_BASE}/me`, {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then(r => {
        if (!r.ok) throw new Error('Unauthorized');
        return r.json();
      })
      .then((data: AuthUser) => {
        setUser(data);
        setLoading(false);
      })
      .catch(() => {
        logout();
        setLoading(false);
      });
  }, [token, logout]);

  // Redirect the browser to the backend OAuth initiation endpoint
  const login = () => {
    globalThis.location.href = AUTH_BASE.replace('/api/auth', '') + '/api/auth/github';
  };

  return (
    <AuthContext.Provider value={{ user, token, isLoading, isAuthenticated: !!user, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextValue => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
};

/** Convenience: returns headers object with Authorization if token is present. */
export function authHeaders(token: string | null): HeadersInit {
  return token ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
               : { 'Content-Type': 'application/json' };
}

export { API_BASE };
