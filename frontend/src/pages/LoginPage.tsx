import React from 'react';
import { Shield, Github, Zap, GitBranch, Network, AlertTriangle } from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';

const LoginPage: React.FC = () => {
  const { login } = useAuth();

  return (
    <div className="min-h-screen bg-slate-950 text-white flex flex-col items-center justify-center relative overflow-hidden">
      {/* Ambient glow */}
      <div className="fixed inset-0 overflow-hidden pointer-events-none">
        <div className="absolute top-0 left-1/4 w-96 h-96 bg-indigo-500/10 rounded-full blur-3xl animate-pulse" />
        <div className="absolute bottom-0 right-1/4 w-96 h-96 bg-purple-500/10 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '1s' }} />
      </div>
      <div className="fixed inset-0 pointer-events-none opacity-10"
        style={{
          backgroundImage: 'linear-gradient(rgba(99,102,241,0.3) 1px, transparent 1px), linear-gradient(90deg, rgba(99,102,241,0.3) 1px, transparent 1px)',
          backgroundSize: '50px 50px'
        }}
      />

      <div className="relative z-10 flex flex-col items-center max-w-md w-full px-6">
        {/* Logo */}
        <div className="flex items-center gap-4 mb-12">
          <div className="relative">
            <div className="absolute inset-0 bg-gradient-to-r from-indigo-500 to-purple-500 rounded-xl blur-lg opacity-60" />
            <div className="relative bg-gradient-to-br from-indigo-600 to-purple-600 p-3 rounded-xl">
              <Shield className="w-10 h-10" />
            </div>
          </div>
          <div>
            <h1 className="text-4xl font-black bg-gradient-to-r from-indigo-400 via-purple-400 to-pink-400 bg-clip-text text-transparent">
              ContextGuard
            </h1>
            <p className="text-slate-400 text-sm font-medium">Intelligent PR Review Platform</p>
          </div>
        </div>

        {/* Card */}
        <div className="w-full bg-slate-800/40 border border-slate-700/50 backdrop-blur-xl rounded-2xl p-8 shadow-2xl">
          <h2 className="text-2xl font-bold text-center mb-2">Welcome back</h2>
          <p className="text-slate-400 text-center text-sm mb-8">
            Sign in with GitHub to see your review requests, analyze PRs, and track history across all your repositories.
          </p>

          <button
            onClick={login}
            className="w-full flex items-center justify-center gap-3 py-3.5 px-6 bg-slate-900 hover:bg-slate-800 border border-slate-600 hover:border-slate-500 rounded-xl font-semibold transition-all duration-200 shadow-lg group"
          >
            <Github className="w-5 h-5 group-hover:scale-110 transition-transform" />
            Sign in with GitHub
          </button>

          <p className="text-xs text-slate-500 text-center mt-4">
            We request <span className="text-slate-400 font-mono">repo</span> and <span className="text-slate-400 font-mono">read:user</span> scopes to access your PRs and repositories.
          </p>
        </div>

        {/* Features */}
        <div className="grid grid-cols-3 gap-4 mt-10 w-full">
          {[
            { icon: <AlertTriangle className="w-5 h-5 text-indigo-400" />, label: 'Risk Scoring' },
            { icon: <Network className="w-5 h-5 text-purple-400" />,    label: 'Call Graph'  },
            { icon: <GitBranch className="w-5 h-5 text-cyan-400" />,    label: 'Blast Radius'},
          ].map(f => (
            <div key={f.label} className="flex flex-col items-center gap-2 p-4 bg-slate-800/30 border border-slate-700/40 rounded-xl">
              {f.icon}
              <span className="text-xs text-slate-400 font-medium">{f.label}</span>
            </div>
          ))}
        </div>

        <p className="mt-10 text-xs text-slate-600">
          ContextGuard · {new Date().getFullYear()}
        </p>
      </div>
    </div>
  );
};

export default LoginPage;
