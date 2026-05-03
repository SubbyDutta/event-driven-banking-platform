import React, { useEffect, useMemo, useRef, useState } from 'react';
import API from '../api';

const STEPS = [
  { key: 'submitted',      label: 'Application received' },
  { key: 'under_review',   label: 'Sent for verification' },
  { key: 'docs_verified',  label: 'Documents verified' },
  { key: 'risk_evaluated', label: 'Risk evaluated' },
  { key: 'decision',       label: 'Decision' },
];

const TERMINAL_STATUSES = ['APPROVED', 'REJECTED', 'FAILED'];
const PENDING_REVIEW_STATUSES = [
  'DOCS_REJECTED', 'RISK_EVALUATED', 'PENDING_ADMIN_DECISION', 'MANUAL_REVIEW',
];

function mapLifecycle(lifecycleStatus) {
  switch (lifecycleStatus) {
    case 'DRAFT':                    return { current: 0, failedAt: null };
    case 'DOCS_UNDER_REVIEW':        return { current: 1, failedAt: null };
    case 'DOCS_VERIFIED':            return { current: 3, failedAt: null };
    case 'DOCS_REJECTED':            return { current: 4, failedAt: null };
    case 'RISK_EVALUATED':           return { current: 4, failedAt: null };
    case 'PENDING_ADMIN_DECISION':   return { current: 4, failedAt: null };
    case 'PENDING_USER_ACCEPTANCE':  return { current: 4, failedAt: null };
    case 'MANUAL_REVIEW':            return { current: 4, failedAt: null };
    case 'APPROVED':                 return { current: STEPS.length, failedAt: null };
    case 'REJECTED':                 return { current: STEPS.length, failedAt: null };
    case 'FAILED':                   return { current: 1, failedAt: 1 };
    default:                         return { current: 0, failedAt: null };
  }
}

function fmtINR(n) {
  if (n == null) return '—';
  return '₹' + Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

function prettyPurpose(p) {
  if (!p) return '—';
  return p.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
}

export default function LoanStatusPanel({ loanAppId, onBack }) {
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const pollRef = useRef(null);

  const load = async () => {
    try {
      const res = await API.get(`/loans/${loanAppId}/status`);
      setStatus(res.data);
      const terminal = TERMINAL_STATUSES.includes(res.data.lifecycleStatus);
      if (terminal && pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    } catch (e) {
      setError(e?.response?.data?.error || 'Failed to load status');
    }
  };

  useEffect(() => {
    load();
    pollRef.current = setInterval(load, 3000);
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loanAppId]);

  const { current, failedAt } = useMemo(
    () => mapLifecycle(status?.lifecycleStatus),
    [status]
  );

  if (error && !status) {
    return (
      <div className="sb-panel" style={{ padding: 40, textAlign: 'center' }}>
        <div className="sb-alert sb-alert-error" style={{ justifyContent: 'center' }}>
          <i className="bi bi-exclamation-triangle-fill" /> {error}
        </div>
        <button className="sb-btn sb-btn-secondary" onClick={onBack} style={{ marginTop: 16 }}>
          Back
        </button>
      </div>
    );
  }
  if (!status) {
    return (
      <div className="sb-panel sb-panel-body">
        <span className="sb-spinner" /> Loading…
      </div>
    );
  }

  const terminal = TERMINAL_STATUSES.includes(status.lifecycleStatus);
  const pendingReview = PENDING_REVIEW_STATUSES.includes(status.lifecycleStatus);
  const awaitingUserAcceptance = status.lifecycleStatus === 'PENDING_USER_ACCEPTANCE';

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: 24, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <div className="sb-eyebrow">Application Reference</div>
          <h2 className="sb-h-section sb-mono" style={{ fontSize: 18 }}>{status.loanAppId}</h2>
        </div>
        <button className="sb-btn sb-btn-secondary" onClick={onBack}>
          ← Back to loans
        </button>
      </div>

      <div className="sb-pipeline">
        {STEPS.map((step, idx) => {
          const isFailed = failedAt != null && idx === failedAt;
          const isDone = idx < current;
          const isCurrent = idx === current && !terminal;
          const cls = isFailed ? 'failed' : isDone ? 'done' : isCurrent ? 'current' : '';
          return (
            <React.Fragment key={step.key}>
              <div className={`sb-step ${cls}`}>
                <div className="dot">{isDone ? '✓' : isFailed ? '×' : String(idx + 1).padStart(2, '0')}</div>
                <div className="text">{step.label}</div>
              </div>
              {idx < STEPS.length - 1 && <div className="sb-step-conn" />}
            </React.Fragment>
          );
        })}
      </div>

      <div className="sb-stats-row" style={{ gridTemplateColumns: '1fr 1fr 1fr' }}>
        <div className="sb-stat-cell">
          <div className="label">Amount</div>
          <div className="value sb-mono">{fmtINR(status.amount)}</div>
        </div>
        <div className="sb-stat-cell">
          <div className="label">Tenure</div>
          <div className="value">{status.tenureMonths} months</div>
        </div>
        <div className="sb-stat-cell">
          <div className="label">Purpose</div>
          <div className="value" style={{ fontFamily: 'inherit', fontSize: 16 }}>
            {prettyPurpose(status.purpose)}
          </div>
        </div>
      </div>

      {terminal && status.lifecycleStatus === 'APPROVED' && <ApprovedCard status={status} />}
      {terminal && (status.lifecycleStatus === 'REJECTED' || status.lifecycleStatus === 'FAILED') && (
        <RejectedCard status={status} />
      )}
      {pendingReview && <PendingReviewCard status={status} />}
      {awaitingUserAcceptance && <OfferAcceptanceCard status={status} onChanged={load} />}
    </div>
  );
}

function ApprovedCard({ status }) {
  return (
    <div className="sb-panel" style={{ marginTop: 24 }}>
      <div className="sb-panel-head">
        <div className="title">
          <i className="bi bi-check-circle-fill" style={{ color: 'var(--positive)', marginRight: 8 }} />
          Loan Approved
        </div>
        <div className="meta">Funds credited</div>
      </div>
      <div className="sb-summary-grid">
        <div className="cell">
          <div className="lbl">Interest Rate</div>
          <div className="val">{status.interestRate ? `${status.interestRate}% p.a.` : '—'}</div>
        </div>
        <div className="cell">
          <div className="lbl">Risk Band</div>
          <div className="val">{status.riskBand || '—'}</div>
        </div>
        <div className="cell">
          <div className="lbl">Monthly EMI</div>
          <div className="val mono">{fmtINR(status.monthlyEmi)}</div>
        </div>
        <div className="cell">
          <div className="lbl">First EMI Due</div>
          <div className="val">
            {status.firstDueDate ? new Date(status.firstDueDate).toLocaleDateString() : '—'}
          </div>
        </div>
      </div>
    </div>
  );
}

function RejectedCard({ status }) {
  return (
    <div className="sb-panel" style={{ marginTop: 24 }}>
      <div className="sb-panel-head">
        <div className="title">
          <i className="bi bi-x-circle-fill" style={{ color: 'var(--accent-3)', marginRight: 8 }} />
          Application Rejected
        </div>
      </div>
      <div className="sb-panel-body" style={{ alignItems: 'flex-start', minHeight: 0 }}>
        {status.decisionReason && (
          <div style={{ fontSize: 13, color: 'var(--ink)', lineHeight: 1.6, marginBottom: 12 }}>
            {status.decisionReason}
          </div>
        )}
        <div style={{ fontSize: 11, color: 'var(--muted)', letterSpacing: '0.04em' }}>
          You may reapply after 30 days.
        </div>
      </div>
    </div>
  );
}

function PendingReviewCard({ status }) {
  return (
    <div className="sb-panel" style={{ marginTop: 24 }}>
      <div className="sb-panel-head">
        <div className="title">
          <i className="bi bi-hourglass-split" style={{ color: 'var(--accent-2)', marginRight: 8 }} />
          Application Under Review
        </div>
      </div>
      <div className="sb-panel-body" style={{ alignItems: 'flex-start', minHeight: 0 }}>
        <div style={{ fontSize: 13, color: 'var(--muted)' }}>
          We're reviewing your application. You'll be notified once a final decision is made.
        </div>
        <div className="sb-mono" style={{ fontSize: 11, color: 'var(--muted)', marginTop: 8 }}>
          Reference: {status.loanAppId}
        </div>
      </div>
    </div>
  );
}

function OfferAcceptanceCard({ status, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [showDecline, setShowDecline] = useState(false);
  const [declineReason, setDeclineReason] = useState('');
  const [error, setError] = useState('');

  const tenureMonths = status.tenureMonths || 6;
  const totalPayable = status.monthlyEmi != null ? status.monthlyEmi * tenureMonths : null;
  const firstDueDate = status.firstDueDate ? new Date(status.firstDueDate).toLocaleDateString() : '—';

  const accept = async () => {
    setBusy(true);
    setError('');
    try {
      await API.post(`/loans/${status.loanAppId}/accept`);
      await onChanged();
    } catch (e) {
      setError(e?.response?.data?.error || 'Failed to accept offer');
    } finally {
      setBusy(false);
    }
  };

  const decline = async () => {
    setBusy(true);
    setError('');
    try {
      await API.post(
        `/loans/${status.loanAppId}/decline`,
        declineReason.trim() ? { reason: declineReason.trim() } : {}
      );
      await onChanged();
    } catch (e) {
      setError(e?.response?.data?.error || 'Failed to decline offer');
    } finally {
      setBusy(false);
      setShowDecline(false);
    }
  };

  return (
    <div className="sb-panel" style={{ marginTop: 24 }}>
      <div className="sb-panel-head">
        <div className="title">
          <i className="bi bi-file-earmark-check" style={{ color: 'var(--positive)', marginRight: 8 }} />
          Loan Offer Ready
        </div>
        <div className="meta">Review and accept terms</div>
      </div>
      <div style={{ padding: 24 }}>
        <div className="sb-summary-grid">
          <div className="cell">
            <div className="lbl">Loan Amount</div>
            <div className="val mono">{fmtINR(status.amount)}</div>
          </div>
          <div className="cell">
            <div className="lbl">Interest Rate</div>
            <div className="val">{status.interestRate ? `${status.interestRate}% p.a.` : '—'}</div>
          </div>
          <div className="cell">
            <div className="lbl">Monthly EMI</div>
            <div className="val mono">{fmtINR(status.monthlyEmi)}</div>
          </div>
          <div className="cell">
            <div className="lbl">Total Payable</div>
            <div className="val mono">{fmtINR(totalPayable)}</div>
          </div>
          <div className="cell">
            <div className="lbl">Tenure</div>
            <div className="val">{tenureMonths} months</div>
          </div>
          <div className="cell">
            <div className="lbl">First EMI Due</div>
            <div className="val">{firstDueDate}</div>
          </div>
        </div>

        <p style={{ fontSize: 13, color: 'var(--muted)', lineHeight: 1.6, margin: '20px 0' }}>
          Review your offer carefully. By accepting, you agree to repay{' '}
          <strong>{fmtINR(totalPayable)}</strong> over <strong>{tenureMonths} months</strong> at{' '}
          <strong>{status.interestRate ? `${status.interestRate}%` : '—'}</strong> per annum.
          EMIs will be auto-debited from your linked account.
        </p>

        {error && (
          <div className="sb-alert sb-alert-error">
            <i className="bi bi-exclamation-triangle-fill" /> {error}
          </div>
        )}

        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <button className="sb-btn sb-btn-primary" onClick={accept} disabled={busy} style={{ flex: 1, minWidth: 200 }}>
            {busy ? <><span className="sb-spinner" /> Processing…</> : 'Accept Offer'}
          </button>
          <button className="sb-btn sb-btn-secondary" onClick={() => setShowDecline(true)} disabled={busy}>
            Decline
          </button>
        </div>

        {showDecline && (
          <div style={{ marginTop: 16, padding: 16, background: 'var(--paper-2)', border: '1px solid var(--rule)' }}>
            <div className="sb-eyebrow" style={{ marginBottom: 8 }}>Decline this offer?</div>
            <textarea
              className="sb-field-input"
              rows={2}
              placeholder="Optional: tell us why (rate too high, no longer needed, etc.)"
              value={declineReason}
              onChange={(e) => setDeclineReason(e.target.value)}
            />
            <div style={{ display: 'flex', gap: 8, marginTop: 12, justifyContent: 'flex-end' }}>
              <button
                className="sb-btn sb-btn-secondary"
                onClick={() => { setShowDecline(false); setDeclineReason(''); }}
                disabled={busy}
              >
                Cancel
              </button>
              <button className="sb-btn sb-btn-primary" onClick={decline} disabled={busy}>
                {busy ? 'Declining…' : 'Confirm decline'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
