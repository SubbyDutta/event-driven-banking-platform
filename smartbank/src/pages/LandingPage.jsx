import React, { useEffect, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { decodeToken, isTokenExpired, clearToken, safeUsername, getRole } from '../utils/auth';

const FEATURES = [
  {
    num: '— 01',
    icon: '⚡',
    iconClass: 'gold',
    title: 'Global P2P Settlement',
    desc:
      'Send assets to anyone, anywhere, in any currency — instantly. The boundary of geography is now obsolete; settlement times measured in seconds, not days.',
    link: 'Read the protocol →',
  },
  {
    num: '— 02',
    icon: '◆',
    iconClass: '',
    title: 'Neural Shield',
    desc:
      'Autonomous transaction monitoring that evolves with every block. Security that thinks ahead of the threat — flagging anomalies before they ever touch your account.',
    link: 'How it works →',
  },
  {
    num: '— 03',
    icon: '∞',
    iconClass: 'gold',
    title: 'Full Aggregation',
    desc:
      "Connect every legacy account, brokerage, and wallet into one stream of truth. Control your entire financial network from a single, beautifully designed surface.",
    link: 'See aggregator →',
  },
];

const HERO_FEED = [
  { ic: '↗', name: 'Transfer · Jane D.', meta: 'SUCCESS · 2s ago', amt: '+₹540.00', tone: 'up' },
  { ic: '⚠', name: 'Security · Unknown IP', meta: 'BLOCKED · Chennai gateway', amt: 'THREAT', tone: 'threat' },
  { ic: '↗', name: 'Settlement · Tokyo', meta: 'CLEARED · 1.2s', amt: '+¥82,400', tone: 'up' },
];

export default function LandingPage() {
  const navigate = useNavigate();
  const [user, setUser] = useState(null);

  useEffect(() => {
    const payload = decodeToken();
    if (!payload) return;
    if (isTokenExpired(payload)) {
      clearToken();
      setUser(null);
      return;
    }
    setUser({ username: safeUsername(payload), role: getRole(payload) });
  }, []);

  const handleAction = () => {
    if (user) {
      navigate(String(user.role || '').includes('ADMIN') ? '/admin' : '/user');
    } else {
      navigate('/signup');
    }
  };

  const time = new Date().toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });

  return (
    <div className="sb-landing">
      <div className="sb-land-nav">
        <div className="sb-auth-brand" style={{ position: 'relative' }}>
          <div className="mark dark" />
          <div className="name dark">SubbyBank Capital</div>
        </div>
        <div className="nav-mid">
          <a href="#personal">Personal</a>
          <a href="#business">Business</a>
          <a href="#capital">Capital</a>
          <a href="#security">Security</a>
          <a href="#company">Company</a>
        </div>
        <div className="nav-right">
          {user ? (
            <button className="cta" onClick={handleAction}>
              Dashboard →
            </button>
          ) : (
            <>
              <Link to="/login" className="signin">Sign in</Link>
              <button className="cta" onClick={handleAction}>
                Open Account →
              </button>
            </>
          )}
        </div>
      </div>

      <div className="sb-hero">
        <div>
          <div className="stamp">
            <span className="dot" />
            <span>Now live · P2P Mobile</span>
          </div>
          <h1>
            Banking with <em>conviction</em>. Built for the <span className="accent">century ahead</span>.
          </h1>
          <p className="sb-lede">
            The fastest, most secure way to manage your financial infrastructure. Instant global
            settlements, AI-driven fraud protection, and the kind of trust you'd expect from an
            institution a hundred years old.
          </p>
          <div className="actions">
            <button className="sb-btn-large" onClick={handleAction}>
              {user ? 'Go to dashboard' : 'Open an account'} →
            </button>
            <a href="#features" className="sb-btn-link">Watch the platform tour</a>
          </div>
          <div className="stats">
            <div className="item">
              <div className="num">$4.8B</div>
              <div className="lbl">Settled · YTD</div>
            </div>
            <div className="item">
              <div className="num">99.99%</div>
              <div className="lbl">Uptime · 90d</div>
            </div>
            <div className="item">
              <div className="num">12K+</div>
              <div className="lbl">Members</div>
            </div>
          </div>
        </div>

        <div className="sb-hero-visual">
          <div className="grid-bg" />
          <div className="marker">
            <span>SYSTEM INTEGRITY</span>
            <span>LIVE · {time} IST</span>
          </div>
          <div className="big-num">100<em>%</em></div>
          <div className="big-sub">Secure · Operational · Encrypted</div>
          <div className="sb-hero-card-list">
            {HERO_FEED.map((card, i) => (
              <div className="sb-hero-card" key={i}>
                <div className="left">
                  <div className="ic">{card.ic}</div>
                  <div>
                    <div className="name">{card.name}</div>
                    <div className="meta">{card.meta}</div>
                  </div>
                </div>
                {card.tone === 'up' ? (
                  <div className="amt up">{card.amt}</div>
                ) : (
                  <div style={{ fontSize: 10, letterSpacing: '0.08em', color: '#E68B7C' }}>
                    {card.amt}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="sb-trust-strip">
        <div className="sb-trust-strip-inner">
          <div className="sb-trust-label">REGULATED · INSURED · AUDITED</div>
          <div className="sb-trust-stamps">
            <div className="stamp">RBI · 274</div>
            <div className="stamp">ISO 27001</div>
            <div className="stamp">SOC 2 Type II</div>
            <div className="stamp">PCI DSS</div>
            <div className="stamp">FDIC Equivalent</div>
          </div>
        </div>
      </div>

      <div className="sb-features-section" id="features">
        <div className="sb-features-head">
          <div className="sb-eyebrow">— Capabilities —</div>
          <h2>
            One bank.
            <br />
            <em>Every</em> capability.
          </h2>
          <p>
            Everything you expect from a modern financial platform — and everything you don't. Built
            with raw performance in mind, refined with the discipline of a private bank.
          </p>
        </div>

        <div className="sb-features-grid">
          {FEATURES.map((f) => (
            <div className="sb-feature" key={f.num}>
              <div className="num">{f.num}</div>
              <div className={`icn ${f.iconClass}`}>{f.icon}</div>
              <h3>{f.title}</h3>
              <p>{f.desc}</p>
              <a href="#features" className="link">{f.link}</a>
            </div>
          ))}
        </div>
      </div>

      <div className="sb-cta-section">
        <div className="sb-cta-inner">
          <div className="sb-eyebrow">— Open Account —</div>
          <h2>Banking, with the <em>weight</em> behind it.</h2>
          <p>
            Two minutes to sign up. Four to verify. Loans approved in minutes, transfers settled in
            seconds. The bank you want — finally built.
          </p>
          <div className="sb-cta-actions">
            <button className="sb-btn-large" onClick={handleAction}>
              {user ? 'Go to dashboard' : 'Open an account'} →
            </button>
            {!user && (
              <Link to="/login" className="sb-btn-link">Already a member? Sign in</Link>
            )}
          </div>
        </div>
      </div>

      <div className="sb-land-foot">
        <div>SubbyBank Capital — Editorial enterprise system · © 2026</div>
        <div className="sb-mono">REF · SB-2026-0501</div>
      </div>
    </div>
  );
}
