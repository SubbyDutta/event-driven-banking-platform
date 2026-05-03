import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate, Link } from 'react-router-dom';
import API from '../api';
import { AuthLeft } from './Login';

function passwordStrength(pw) {
  let score = 0;
  if (!pw) return { score: 0, label: 'Empty', color: 'var(--rule)' };
  if (pw.length >= 8) score += 1;
  if (/[A-Z]/.test(pw)) score += 1;
  if (/[a-z]/.test(pw)) score += 1;
  if (/[0-9]/.test(pw)) score += 1;
  if (/[^A-Za-z0-9]/.test(pw)) score += 1;
  const map = [
    { label: 'Very weak', color: 'var(--accent-3)' },
    { label: 'Weak', color: '#C97B0E' },
    { label: 'Fair', color: '#C97B0E' },
    { label: 'Good', color: 'var(--positive)' },
    { label: 'Strong', color: 'var(--positive)' },
    { label: 'Excellent', color: 'var(--positive)' },
  ];
  return { score, ...map[score] };
}

export default function ResetPassword() {
  const navigate = useNavigate();
  const location = useLocation();
  const initialEmail = (location.state && location.state.email) || '';

  const [email, setEmail] = useState(initialEmail);
  const [otp, setOtp] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState(null);

  useEffect(() => {
    if (!initialEmail) setEmail('');
  }, [initialEmail]);

  const strength = passwordStrength(newPassword);

  function validateForm() {
    if (!email) return 'Email is required.';
    if (!otp) return 'OTP is required.';
    if (otp.length < 4) return 'OTP looks too short.';
    if (!newPassword) return 'New password is required.';
    if (newPassword.length < 8) return 'Password must be at least 8 characters.';
    if (newPassword !== confirm) return 'Passwords do not match.';
    return null;
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setMessage(null);
    const err = validateForm();
    if (err) {
      setMessage({ type: 'error', text: err });
      return;
    }
    setSubmitting(true);
    try {
      await API.post('/auth/reset-password', { email, otp, newPassword });
      setMessage({ type: 'success', text: 'Password reset successful. Redirecting to login…' });
      setTimeout(() => navigate('/login'), 1400);
    } catch {
      setMessage({ type: 'error', text: 'Reset failed. Check OTP and try again.' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="sb-auth-shell">
      <AuthLeft variant="secure" />
      <div className="sb-auth-right">
        <div className="corner">
          Didn't get OTP? <Link to="/forgot-password">Request again →</Link>
        </div>
        <div className="sb-auth-card">
          <div className="sb-eyebrow">New Credentials</div>
          <h1>Set a new <em>password</em>.</h1>
          <p className="sub">
            Enter the OTP we sent to your email and pick a strong new password. We recommend at least
            12 characters with a mix of letters, numbers, and symbols.
          </p>

          {message && (
            <div className={`sb-alert ${message.type === 'error' ? 'sb-alert-error' : 'sb-alert-success'}`}>
              <i className={`bi ${message.type === 'error' ? 'bi-exclamation-triangle-fill' : 'bi-check-circle-fill'}`} />
              {message.text}
            </div>
          )}

          <form onSubmit={handleSubmit}>
            <div className="sb-field">
              <div className="sb-field-label">Email</div>
              <input
                type="email"
                className="sb-field-input"
                placeholder="you@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value.trim())}
                required
              />
            </div>

            <div className="sb-field">
              <div className="sb-field-label">One-Time Passcode</div>
              <input
                inputMode="numeric"
                className="sb-field-input mono"
                style={{ letterSpacing: '0.4em', fontWeight: 500 }}
                placeholder="123456"
                value={otp}
                onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 8))}
                autoComplete="one-time-code"
                required
              />
            </div>

            <div className="sb-field">
              <div className="sb-field-label">New Password</div>
              <input
                type="password"
                className="sb-field-input"
                placeholder="At least 8 characters"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                autoComplete="new-password"
                required
              />
              {newPassword && (
                <div style={{ marginTop: 10 }}>
                  <div style={{ height: 4, background: 'var(--rule)', borderRadius: 0, overflow: 'hidden' }}>
                    <div
                      style={{
                        height: '100%',
                        width: `${(strength.score / 5) * 100}%`,
                        background: strength.color,
                        transition: 'width 0.25s',
                      }}
                    />
                  </div>
                  <div
                    style={{
                      fontSize: 10,
                      fontWeight: 500,
                      color: strength.color,
                      marginTop: 6,
                      textTransform: 'uppercase',
                      letterSpacing: '0.1em',
                    }}
                  >
                    {strength.label}
                  </div>
                </div>
              )}
            </div>

            <div className="sb-field">
              <div className="sb-field-label">Confirm Password</div>
              <input
                type="password"
                className="sb-field-input"
                placeholder="Re-enter the new password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                autoComplete="new-password"
                required
              />
              {confirm && newPassword && confirm !== newPassword && (
                <div style={{ fontSize: 11, color: 'var(--accent-3)', marginTop: 6 }}>
                  Passwords don't match yet.
                </div>
              )}
            </div>

            <button
              type="submit"
              className="sb-btn sb-btn-primary sb-btn-block"
              disabled={submitting}
              style={{ padding: 14, marginTop: 8 }}
            >
              {submitting ? <><span className="sb-spinner" /> Resetting…</> : 'Reset Password →'}
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
        </div>
      </div>
    </div>
  );
}
