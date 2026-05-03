import React, { useState, useEffect, useMemo } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import API from '../api';
import { decodeToken, isTokenExpired, setToken, clearToken, getRole } from '../utils/auth';

export default function Login() {
  const [form, setForm] = useState({ username: '', password: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [userRole, setUserRole] = useState(null);
  const navigate = useNavigate();

  useEffect(() => {
    const decoded = decodeToken();
    if (decoded && !isTokenExpired(decoded)) {
      setIsLoggedIn(true);
      setUserRole(getRole(decoded));
    } else if (decoded) {
      clearToken();
    }
  }, []);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await API.post('/auth/login', form);
      const token = res.data.token ?? res.data?.accessToken;
      if (!token || !setToken(token)) throw new Error('No token received');
      const role = getRole(decodeToken(token));
      navigate(role.includes('ADMIN') ? '/admin' : '/user');
    } catch {
      setError('Invalid credentials. Please check your username and password.');
    } finally {
      setLoading(false);
    }
  };

  if (isLoggedIn) {
    return (
      <div className="sb-auth-shell">
        <AuthLeft variant="welcome" />
        <div className="sb-auth-right">
          <div className="sb-auth-card">
            <div className="sb-eyebrow">Already signed in</div>
            <h1>You're <em>logged in</em>.</h1>
            <p className="sub">Continue to your dashboard, or sign out and use a different account.</p>
            <button
              className="sb-btn sb-btn-primary sb-btn-block"
              style={{ padding: 14, marginBottom: 12 }}
              onClick={() => navigate(String(userRole ?? '').includes('ADMIN') ? '/admin' : '/user')}
            >
              Go to Dashboard →
            </button>
            <button
              className="sb-btn sb-btn-secondary sb-btn-block"
              style={{ padding: 14 }}
              onClick={() => {
                clearToken();
                setIsLoggedIn(false);
                setUserRole(null);
              }}
            >
              Sign out
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="sb-auth-shell">
      <AuthLeft variant="welcome" />
      <div className="sb-auth-right">
        <div className="corner">
          New to SubbyBank? <Link to="/signup">Create account →</Link>
        </div>
        <div className="sb-auth-card">
          <div className="sb-eyebrow">Welcome Back</div>
          <h1>Sign <em>in</em>.</h1>
          <p className="sub">
            Enter your credentials to access your secure banking workspace and manage your finances
            with confidence.
          </p>

          {error && (
            <div className="sb-alert sb-alert-error">
              <i className="bi bi-exclamation-triangle-fill" /> {error}
            </div>
          )}

          <form onSubmit={handleSubmit}>
            <div className="sb-field">
              <div className="sb-field-label">Username</div>
              <input
                type="text"
                className="sb-field-input"
                placeholder="your_username"
                value={form.username}
                onChange={(e) => setForm({ ...form, username: e.target.value })}
                required
                autoFocus
              />
            </div>
            <div className="sb-field">
              <div className="sb-field-label">
                Password
                <span style={{ marginLeft: 'auto', fontSize: 10 }}>
                  <Link to="/forgot-password" style={{ color: 'var(--ink)', borderBottom: '1px solid var(--ink)' }}>
                    Forgot?
                  </Link>
                </span>
              </div>
              <input
                type="password"
                className="sb-field-input"
                placeholder="••••••••••••"
                value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
                required
              />
            </div>

            <button
              type="submit"
              className="sb-btn sb-btn-primary sb-btn-block"
              disabled={loading}
              style={{ padding: 14, marginTop: 8 }}
            >
              {loading ? <><span className="sb-spinner" /> Signing in…</> : 'Sign In →'}
            </button>
          </form>

          <div className="sb-auth-foot-link">
            <span>Protected by 2FA + Device fingerprint</span>
            <Link to="/forgot-password">Need help?</Link>
          </div>
        </div>
      </div>
    </div>
  );
}

const VARIANT_CONFIG = {
  welcome: {
    eyebrow: 'PRIVATE BANKING · EST. MMXXIV',
    headline: <>The <em>quiet</em> kind of banking.</>,
    sub: 'Built for people who want their financial infrastructure to feel less like software and more like a private institution.',
    feed: [
      { time: '14:22', label: 'Settlement · Tokyo', meta: 'CLEARED · 1.2s', tone: 'ok' },
      { time: '14:21', label: 'Inflow · Salary', meta: 'CREDITED · ₹1,82,400', tone: 'ok' },
      { time: '14:18', label: 'Security · Unknown IP', meta: 'BLOCKED · Mumbai', tone: 'warn' },
    ],
  },
  join: {
    eyebrow: 'OPEN ACCOUNT · UNDER FOUR MINUTES',
    headline: <>A bank that <em>opens</em> in minutes.</>,
    sub: 'Open an account, complete KYC, and apply for credit — all from the same surface. Most decisions clear in minutes, not days.',
    feed: [
      { time: '—', label: 'KYC · Aadhaar + PAN', meta: 'AI VERIFICATION · 5 STAGES', tone: 'ok' },
      { time: '—', label: 'Account · INR Savings', meta: 'INSTANT · NO MINIMUM', tone: 'ok' },
      { time: '—', label: 'Credit · Personal Loan', meta: 'DECISIONS IN MINUTES', tone: 'ok' },
    ],
  },
  secure: {
    eyebrow: 'SECURITY CHARTER',
    headline: <>Security is the <em>boundary condition</em>.</>,
    sub: 'Every decision we make starts with one constraint: your money and identity stay yours. Encryption, audit trails, and proof of access — by design.',
    feed: [
      { time: '24×7', label: 'Encryption · AES-256', meta: 'AT REST · IN TRANSIT', tone: 'ok' },
      { time: '5/5', label: 'KYC Pipeline', meta: 'OCR · ID · COMPLIANCE · FRAUD · DECISION', tone: 'ok' },
      { time: 'LIVE', label: 'Audit · Every action', meta: 'IMMUTABLE LEDGER', tone: 'ok' },
    ],
  },
};

function AuthLeft({ variant = 'welcome' }) {
  const cfg = VARIANT_CONFIG[variant] || VARIANT_CONFIG.welcome;

  const stats = useMemo(
    () => [
      { num: '$4.8B', lbl: 'Settled YTD' },
      { num: '99.99%', lbl: 'Uptime · 90d' },
      { num: '12K+', lbl: 'Members' },
    ],
    []
  );

  const time = new Date().toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });

  return (
    <div className="sb-auth-left">
      <div className="sb-auth-left-grid" />

      <div className="sb-auth-left-top">
        <div className="sb-auth-brand">
          <div className="mark" />
          <div className="name">SubbyBank Capital</div>
        </div>
        <div className="sb-auth-status">
          <span className="dot" />
          <span>LIVE · {time} IST</span>
        </div>
      </div>

      <div className="sb-auth-showcase">
        <div className="sb-auth-eyebrow">{cfg.eyebrow}</div>
        <h2 className="sb-auth-headline">{cfg.headline}</h2>
        <p className="sb-auth-sub">{cfg.sub}</p>

        <div className="sb-auth-stats">
          {stats.map((s) => (
            <div key={s.lbl} className="sb-auth-stat">
              <div className="num">{s.num}</div>
              <div className="lbl">{s.lbl}</div>
            </div>
          ))}
        </div>

        <div className="sb-auth-feed">
          <div className="sb-auth-feed-head">
            <span>SYSTEM ACTIVITY</span>
            <span className="sb-mono">REF · A87-2026</span>
          </div>
          {cfg.feed.map((f, i) => (
            <div key={i} className="sb-auth-feed-row">
              <div className="time">{f.time}</div>
              <div className="body">
                <div className="label">{f.label}</div>
                <div className="meta">{f.meta}</div>
              </div>
              <div className={`badge ${f.tone}`}>
                {f.tone === 'warn' ? '×' : '✓'}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="sb-auth-foot">
        <div>EST. MMXXIV · LICENSED IN INDIA</div>
        <div className="stamps">
          <div className="stamp">RBI · 274</div>
          <div className="stamp">ISO 27001</div>
          <div className="stamp">SOC 2</div>
        </div>
      </div>
    </div>
  );
}

export { AuthLeft };
