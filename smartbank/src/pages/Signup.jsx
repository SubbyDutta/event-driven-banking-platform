import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import API from '../api';
import { AuthLeft } from './Login';

export default function Signup() {
  const [form, setForm] = useState({
    username: '',
    firstname: '',
    lastname: '',
    dob: '',
    password: '',
    confirmPassword: '',
    email: '',
    mobile: '',
  });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [success, setSuccess] = useState(false);
  const navigate = useNavigate();

  const updateForm = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');

    if (form.password !== form.confirmPassword) {
      setError('Passwords do not match.');
      return;
    }

    setLoading(true);
    try {
      const payload = {
        username: form.username.trim(),
        firstname: form.firstname.trim(),
        lastname: form.lastname.trim(),
        dob: form.dob,
        password: form.password,
        email: form.email.trim(),
        mobile: form.mobile.trim(),
      };

      await API.post('/auth/signup', payload);
      setSuccess(true);
      setTimeout(() => navigate('/login'), 1500);
    } catch (err) {
      const backendMessage =
        err?.response?.data?.message || err?.response?.data || err?.message || 'Signup failed';
      setError(typeof backendMessage === 'string' ? backendMessage : 'Signup failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="sb-auth-shell">
      <AuthLeft variant="join" />
      <div className="sb-auth-right" style={{ padding: '48px 64px' }}>
        <div className="corner">
          Already a member? <Link to="/login">Sign in →</Link>
        </div>
        <div className="sb-auth-card">
          <div className="sb-eyebrow">Open Account</div>
          <h1>Become a <em>member</em>.</h1>
          <p className="sub">Quick signup · We'll verify your identity in the next step.</p>

          {error && (
            <div className="sb-alert sb-alert-error">
              <i className="bi bi-exclamation-triangle-fill" /> {error}
            </div>
          )}

          {success && (
            <div className="sb-alert sb-alert-success">
              <i className="bi bi-check-circle-fill" />
              Account created! Redirecting to login…
            </div>
          )}

          <form onSubmit={handleSubmit}>
            <div className="sb-field-row" style={{ marginBottom: 18 }}>
              <div className="sb-field" style={{ marginBottom: 0 }}>
                <div className="sb-field-label">First Name</div>
                <input
                  className="sb-field-input"
                  placeholder="Your first name"
                  value={form.firstname}
                  onChange={(e) => updateForm('firstname', e.target.value)}
                  required
                />
              </div>
              <div className="sb-field" style={{ marginBottom: 0 }}>
                <div className="sb-field-label">Last Name</div>
                <input
                  className="sb-field-input"
                  placeholder="Your last name"
                  value={form.lastname}
                  onChange={(e) => updateForm('lastname', e.target.value)}
                  required
                />
              </div>
            </div>

            <div className="sb-field">
              <div className="sb-field-label">Username</div>
              <input
                className="sb-field-input"
                placeholder="pick_a_username"
                value={form.username}
                onChange={(e) => updateForm('username', e.target.value)}
                required
              />
            </div>

            <div className="sb-field">
              <div className="sb-field-label">Email</div>
              <input
                type="email"
                className="sb-field-input"
                placeholder="hello@example.com"
                value={form.email}
                onChange={(e) => updateForm('email', e.target.value)}
                required
              />
            </div>

            <div className="sb-field-row" style={{ marginBottom: 18 }}>
              <div className="sb-field" style={{ marginBottom: 0 }}>
                <div className="sb-field-label">Phone</div>
                <input
                  className="sb-field-input mono"
                  placeholder="+91 ··· ··· ····"
                  value={form.mobile}
                  onChange={(e) => updateForm('mobile', e.target.value)}
                  required
                />
              </div>
              <div className="sb-field" style={{ marginBottom: 0 }}>
                <div className="sb-field-label">Date of Birth</div>
                <input
                  type="date"
                  className="sb-field-input mono"
                  max={new Date().toISOString().slice(0, 10)}
                  value={form.dob}
                  onChange={(e) => updateForm('dob', e.target.value)}
                  required
                />
              </div>
            </div>

            <div className="sb-field">
              <div className="sb-field-label">Password</div>
              <input
                type="password"
                className="sb-field-input"
                placeholder="At least 8 characters"
                value={form.password}
                onChange={(e) => updateForm('password', e.target.value)}
                required
              />
            </div>

            <div className="sb-field">
              <div className="sb-field-label">Confirm Password</div>
              <input
                type="password"
                className="sb-field-input"
                placeholder="Repeat password"
                value={form.confirmPassword}
                onChange={(e) => updateForm('confirmPassword', e.target.value)}
                required
              />
            </div>

            <button
              type="submit"
              className="sb-btn sb-btn-primary sb-btn-block"
              disabled={loading || success}
              style={{ padding: 14, marginTop: 12 }}
            >
              {loading ? <><span className="sb-spinner" /> Creating…</> : 'Create Account →'}
            </button>
          </form>

          <div className="sb-auth-foot-link">
            <span>By continuing, you accept our <Link to="/login">Terms</Link></span>
            <span className="sb-mono">v 4.2.1</span>
          </div>
        </div>
      </div>
    </div>
  );
}
