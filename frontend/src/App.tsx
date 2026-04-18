import React, { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import ErrorBoundary from './components/ErrorBoundary';
import ReviewPage from './components/ReviewPage';
import ContextGuardPresentation from './components/ContextGuardPresentation';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import { AuthProvider, useAuth, TOKEN_KEY } from './contexts/AuthContext';
import '../styles.css';

/**
 * Unprotected landing page for the OAuth redirect.
 * URL: /auth/callback?token=JWT
 *
 * The ProtectedRoute on /dashboard would reject the user before the token
 * is stored, so this separate unprotected route captures it first, writes
 * it to localStorage, then hard-navigates to /dashboard (triggering a full
 * re-mount of AuthProvider so isAuthenticated becomes true).
 */
const OAuthCallbackPage: React.FC = () => {
  const location = useLocation();

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const jwt    = params.get('token');
    const error  = params.get('error');

    if (jwt) {
      localStorage.setItem(TOKEN_KEY, jwt);
      globalThis.location.replace('/dashboard');
    } else {
      // Auth failed — go to login with error message
      globalThis.location.replace('/login' + (error ? `?error=${error}` : ''));
    }
  }, [location]);

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-center">
      <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
    </div>
  );
};

/** Redirects to /login if not authenticated, shows children otherwise. */
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-950 flex items-center justify-center">
        <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />;
};

const AppRoutes: React.FC = () => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-950 flex items-center justify-center">
        <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  return (
    <Routes>
      {/* Root: redirect to dashboard if logged in, else login */}
      <Route path="/" element={
        isAuthenticated ? <Navigate to="/dashboard" replace /> : <Navigate to="/login" replace />
      } />

      <Route path="/login" element={
        isAuthenticated ? <Navigate to="/dashboard" replace /> : <LoginPage />
      } />

      {/* OAuth callback — unprotected, stores JWT then hard-redirects to /dashboard */}
      <Route path="/auth/callback" element={<OAuthCallbackPage />} />

      {/* Main dashboard — protected */}
      <Route path="/dashboard" element={
        <ProtectedRoute>
          <DashboardPage />
        </ProtectedRoute>
      } />

      {/* Analysis result page — accessible without login (sharable links) */}
      <Route path="/review/:analysisId" element={<ReviewPage />} />

      <Route path="/presentation" element={<ContextGuardPresentation />} />

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
};

const App: React.FC = () => (
  <ErrorBoundary>
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  </ErrorBoundary>
);

export default App;
