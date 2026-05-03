import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import API from '../api';
import { AuthLeft } from './Login';

export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState(null);
  const navigate = useNavigate();

  const validateEmail = (v) => /\S+@\S+\.\S+/.test(v);

  async function handleSubmit(e) {
    e.preventDefault();
    setMessage(null);
    if (!email || !validateEmail(email)) {
      setMessage({ type: 'error', text: 'Please enter a valid email address.' });
      return;
    }

    setSending(true);
    try {
      await API.post('/auth/forgot-password', { email });
      setMessage({ type: 'success', text: 'OTP sent. Check your inbox — redirecting…' });
      setTimeout(() => navigate('/reset-password', { state: { email } }), 1500);
    } catch {
      setMessage({ type: 'error', text: 'Failed to send OTP. Please try again.' });
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="sb-auth-shell">
      <AuthLeft variant="secure" />
      <div className="sb-auth-right">
        <div className="corner">
          Remembered? <Link to="/login">Sign in →</Link>
        </div>
        <div className="sb-auth-card">
          <div className="sb-eyebrow">Account Recovery</div>
          <h1>Reset your <em>password</em>.</h1>
          <p className="sub">
            Enter your registered email and we'll send a one-time passcode to verify it's you. Your
            account stays locked until verification completes.
          </p>

          {message && (
            <div className={`sb-alert ${message.type === 'error' ? 'sb-alert-error' : 'sb-alert-success'}`}>
              <i className={`bi ${message.type === 'error' ? 'bi-exclamation-triangle-fill' : 'bi-check-circle-fill'}`} />
              {message.text}
            </div>
          )}

          <form onSubmit={handleSubmit}>
            <div className="sb-field">
              <div className="sb-field-label">Registered Email</div>
              <input
                type="email"
                className="sb-field-input"
                placeholder="you@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value.trim())}
                autoComplete="email"
                autoFocus
                required
              />
              <div className="sb-field-help">OTP delivered within seconds · Encrypted in transit</div>
            </div>

            <button
              type="submit"
              className="sb-btn sb-btn-primary sb-btn-block"
              disabled={sending}
              style={{ padding: 14, marginTop: 8 }}
            >
              {sending ? <><span className="sb-spinner" /> Sending OTP…</> : 'Send OTP →'}
            </button>

            <button
              type="button"
              className="sb-btn sb-btn-secondary sb-btn-block"
              style={{ padding: 14, marginTop: 12 }}
              onClick={() => navigate('/login')}
            >
              Back to login
            </button>
          </form>

          <div className="sb-auth-foot-link">
            <span>Account auto-locks after 5 failed attempts</span>
            <Link to="/signup">Create account</Link>
          </div>
        </div>
      </div>
    </div>
  );
}
