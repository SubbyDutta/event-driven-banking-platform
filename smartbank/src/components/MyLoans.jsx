import React, { useEffect, useMemo, useRef, useState } from 'react';
import API from '../api';
import { v4 as uuidv4 } from 'uuid';
import LoanStatusPanel from './LoanStatusPanel';

const fmtINR = (v) =>
  typeof v === 'number'
    ? v.toLocaleString('en-IN', { style: 'currency', currency: 'INR' })
    : v;

const IN_FLIGHT = new Set([
  'DRAFT', 'DOCS_UNDER_REVIEW', 'DOCS_VERIFIED', 'DOCS_REJECTED',
  'RISK_EVALUATED', 'PENDING_ADMIN_DECISION', 'PENDING_USER_ACCEPTANCE', 'MANUAL_REVIEW',
]);

const LIFECYCLE_PILL = {
  DRAFT:                   { tone: 'muted', label: 'Draft' },
  DOCS_UNDER_REVIEW:       { tone: 'warn',  label: 'Pending review' },
  DOCS_VERIFIED:           { tone: 'warn',  label: 'Pending review' },
  DOCS_REJECTED:           { tone: 'warn',  label: 'Pending review' },
  RISK_EVALUATED:          { tone: 'warn',  label: 'Pending review' },
  PENDING_ADMIN_DECISION:  { tone: 'warn',  label: 'Pending review' },
  PENDING_USER_ACCEPTANCE: { tone: 'warn',  label: 'Offer ready' },
  MANUAL_REVIEW:           { tone: 'warn',  label: 'Pending review' },
  APPROVED:                { tone: 'success', label: 'Approved' },
  REJECTED:                { tone: 'error', label: 'Rejected' },
  FAILED:                  { tone: 'muted', label: 'Failed' },
};

export default function LoanRepaymentPanel() {
  const [loans, setLoans] = useState([]);
  const [selectedLoanId, setSelectedLoanId] = useState('');
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(false);
  const [btnBusy, setBtnBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [history, setHistory] = useState([]);
  const [originations, setOriginations] = useState([]);
  const [inspectLoanAppId, setInspectLoanAppId] = useState(null);

  const payLock = useRef(false);
  const [confirmDlg, setConfirmDlg] = useState(null);

  useEffect(() => {
    let mounted = true;
    (async () => {
      try {
        setLoading(true);
        const res = await API.get('/repay/user/approved');
        if (!mounted) return;
        setLoans(res.data || []);
        if ((res.data || []).length && !selectedLoanId) {
          setSelectedLoanId(String(res.data[0].id));
        }
      } catch (err) {
        console.error(err);
        if (!mounted) return;
        setMessage({ type: 'error', text: 'Failed to load your loans.' });
      } finally {
        if (mounted) setLoading(false);
      }
    })();

    (async () => {
      try {
        const hRes = await API.get('/loan/loans/myuserloan');
        if (mounted) setHistory(hRes.data || []);
      } catch (e) {
        console.error('Failed to load loan history', e);
      }
    })();

    (async () => {
      try {
        const oRes = await API.get('/loans/my', { params: { size: 20 } });
        if (mounted) setOriginations(oRes.data?.content || []);
      } catch (e) {
        console.error('Failed to load V4 loan applications', e);
      }
    })();

    return () => (mounted = false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selectedLoanId) {
      setSummary(null);
      return;
    }
    let mounted = true;
    (async () => {
      try {
        setLoading(true);
        const res = await API.get(`/repay/summary/${selectedLoanId}`);
        if (!mounted) return;
        setSummary(res.data);
        setMessage(null);
      } catch (err) {
        console.error(err);
        if (!mounted) return;
        setMessage({ type: 'error', text: 'Failed to load loan summary.' });
      } finally {
        if (mounted) setLoading(false);
      }
    })();
    return () => (mounted = false);
  }, [selectedLoanId]);

  const canPay = useMemo(() => {
    if (!summary) return false;
    return summary.remainingBalance > 0 && summary.monthsRemaining >= 0;
  }, [summary]);

  const payEmiAmount = useMemo(() => {
    if (!summary) return 0;
    return Math.min(summary.monthlyEmi, summary.remainingBalance);
  }, [summary]);

  const payFullAmount = summary?.remainingBalance ?? 0;

  const requestPay = (amount, label, kind) => {
    if (!summary?.loanId || payLock.current) return;
    setConfirmDlg({ amount, label, kind });
  };

  const performPay = async () => {
    if (!summary?.loanId || !confirmDlg) return;
    if (payLock.current) return;
    payLock.current = true;
    const { amount } = confirmDlg;

    try {
      setBtnBusy(true);
      const idempotencyKey = uuidv4();
      await API.post(
        `/repay/repay/${summary.loanId}`,
        { amount },
        { headers: { 'idempotency-Key': idempotencyKey } }
      );
      const [sumRes, loansRes] = await Promise.all([
        API.get(`/repay/summary/${summary.loanId}`),
        API.get('/repay/user/approved'),
      ]);
      setSummary(sumRes.data);
      setLoans(loansRes.data || []);
      setMessage({
        type: 'success',
        text: `Payment successful. Remaining: ${fmtINR(sumRes.data.remainingBalance)}.`,
      });
      setConfirmDlg(null);
    } catch (err) {
      console.error(err);
      const apiMsg =
        err?.response?.data?.error || err?.response?.data?.message || 'Payment failed.';
      setMessage({ type: 'error', text: apiMsg });
      setConfirmDlg(null);
    } finally {
      setBtnBusy(false);
      payLock.current = false;
    }
  };

  if (inspectLoanAppId) {
    return <LoanStatusPanel loanAppId={inspectLoanAppId} onBack={() => setInspectLoanAppId(null)} />;
  }

  const inFlight = originations.filter((o) => IN_FLIGHT.has(o.lifecycleStatus));

  return (
    <>

      {inFlight.length > 0 && (
        <div className="sb-panel" style={{ marginBottom: 24 }}>
          <div className="sb-panel-head">
            <div className="title">In-flight Application{inFlight.length === 1 ? '' : 's'}</div>
            <div className="meta">{inFlight.length} active</div>
          </div>
          <div>
            {inFlight.map((o) => {
              const pill = LIFECYCLE_PILL[o.lifecycleStatus] || { tone: 'muted', label: o.lifecycleStatus };
              return (
                <div
                  key={o.loanAppId}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    padding: '14px 24px',
                    borderBottom: '1px solid var(--rule-soft)',
                    gap: 12,
                  }}
                >
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 13, fontWeight: 500 }}>
                      {fmtINR(o.amount)} · {o.purpose}
                    </div>
                    <div className="sb-mono" style={{ fontSize: 11, color: 'var(--muted)' }}>
                      {o.loanAppId?.slice(0, 8)}…
                    </div>
                  </div>
                  <span className={`sb-tag-pill ${pill.tone}`}>{pill.label}</span>
                  <button
                    className="sb-btn sb-btn-secondary"
                    style={{ padding: '6px 14px', fontSize: 12 }}
                    onClick={() => setInspectLoanAppId(o.loanAppId)}
                  >
                    View progress
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      <div className="sb-repay-section">
        <div className="sb-repay-head">
          <div className="sb-seal">
            <i className="bi bi-cash-stack" />
          </div>
          <h2>
            Loan <em>Repayment</em>
          </h2>
          <p>
            Pick a loan, review the schedule, and pay either an EMI installment or settle the full
            outstanding balance early — without penalty.
          </p>
        </div>

        <div className="sb-repay-body">
          <div className="sb-select-group">
            <div className="label">Choose Loan</div>
            <select
              className="sb-select-input"
              value={selectedLoanId}
              onChange={(e) => setSelectedLoanId(e.target.value)}
              disabled={loading || loans.length === 0}
            >
              {loans.length === 0 && <option value="">No approved loans</option>}
              {loans.map((ln) => (
                <option key={ln.id} value={ln.id}>
                  {`Loan #${ln.id} — ${fmtINR(ln.amount)} — ${ln.status}`}
                </option>
              ))}
            </select>
          </div>

          <div className="sb-select-group">
            <div className="label">Repayment Summary</div>
            {!summary ? (
              <div className="sb-summary-empty">
                <div className="h">Select a loan to view details</div>
                <div className="p">Schedule, principal, interest and outstanding will display here</div>
              </div>
            ) : (
              <div className="sb-summary-grid">
                <div className="cell">
                  <div className="lbl">Total Amount</div>
                  <div className="val mono">{fmtINR(summary.totalAmount)}</div>
                </div>
                <div className="cell">
                  <div className="lbl">Remaining</div>
                  <div className="val mono" style={{ color: 'var(--positive)' }}>
                    {fmtINR(summary.remainingBalance)}
                  </div>
                </div>
                <div className="cell">
                  <div className="lbl">Monthly EMI</div>
                  <div className="val mono">{fmtINR(summary.monthlyEmi)}</div>
                </div>
                <div className="cell">
                  <div className="lbl">Next Due</div>
                  <div className="val">
                    {summary.nextDueDate ? new Date(summary.nextDueDate).toLocaleDateString() : '—'}
                  </div>
                </div>
              </div>
            )}
          </div>

          {message && (
            <div
              className={`sb-alert ${
                message.type === 'success' ? 'sb-alert-success' : 'sb-alert-error'
              }`}
              style={{ gridColumn: '1 / -1', marginBottom: 0 }}
            >
              <i
                className={`bi ${
                  message.type === 'success' ? 'bi-check-circle-fill' : 'bi-exclamation-triangle-fill'
                }`}
              />
              {message.text}
            </div>
          )}

          {!summary && loans.length === 0 && (
            <div className="sb-alert sb-alert-error" style={{ gridColumn: '1 / -1', marginBottom: 0 }}>
              <span className="code">[ 403 ]</span>
              <span>
                You don't have any approved loans yet. Once an application is approved, your repayment
                schedule will appear here.
              </span>
            </div>
          )}

          <div className="sb-pay-actions">
            <button
              className="sb-pay-btn primary"
              disabled={!canPay || btnBusy}
              onClick={() => requestPay(payEmiAmount, 'EMI payment', 'emi')}
            >
              <div className="lbl">Pay EMI {summary ? `(${fmtINR(payEmiAmount)})` : ''}</div>
              <div className="sub">Next installment</div>
            </button>
            <button
              className="sb-pay-btn"
              disabled={!canPay || btnBusy}
              onClick={() => requestPay(payFullAmount, 'FULL payment', 'full')}
            >
              <div className="lbl">Pay Full {summary ? `(${fmtINR(payFullAmount)})` : ''}</div>
              <div className="sub">Settle outstanding</div>
            </button>
          </div>
        </div>
      </div>

      {confirmDlg && (
        <div className="sb-modal-backdrop" onClick={() => !btnBusy && setConfirmDlg(null)}>
          <div className="sb-modal" onClick={(e) => e.stopPropagation()}>
            <div className="sb-modal-head">
              <div className="corner-mark">REF · LOAN-{summary?.loanId}</div>
              <div className="sb-seal" style={{ marginTop: 12 }}>
                <i className="bi bi-cash-stack" />
              </div>
              <h3>Confirm {confirmDlg.kind === 'full' ? 'Full Settlement' : 'EMI Payment'}</h3>
              <p>
                {confirmDlg.kind === 'full'
                  ? 'You are about to settle this loan in full. The amount will be deducted from your linked account immediately.'
                  : 'You are about to pay your next EMI installment. The amount will be deducted from your linked account.'}
              </p>
            </div>
            <div className="sb-modal-body">
              <div className="sb-txn-summary">
                <div className="row">
                  <span className="lbl">LOAN ID</span>
                  <span className="val">#{summary?.loanId}</span>
                </div>
                <div className="row">
                  <span className="lbl">TYPE</span>
                  <span className="val">{confirmDlg.kind === 'full' ? 'FULL SETTLEMENT' : 'EMI'}</span>
                </div>
                <div className="row">
                  <span className="lbl">REMAINING AFTER</span>
                  <span className="val">
                    {fmtINR(Math.max(0, (summary?.remainingBalance || 0) - confirmDlg.amount))}
                  </span>
                </div>
                <div className="row amt">
                  <span className="lbl">AMOUNT</span>
                  <span className="val">{fmtINR(confirmDlg.amount)}</span>
                </div>
              </div>

              <div className="sb-alert sb-alert-warn" style={{ marginBottom: 16 }}>
                <i className="bi bi-info-circle-fill" />
                <span>This action cannot be undone. Proceed only if you intend to make the payment now.</span>
              </div>

              <div className="sb-btn-row" style={{ borderTop: 'none', paddingTop: 0 }}>
                <button
                  className="sb-btn sb-btn-primary"
                  style={{ flex: 1 }}
                  onClick={performPay}
                  disabled={btnBusy}
                >
                  {btnBusy ? <><span className="sb-spinner" /> Processing…</> : 'Confirm & Pay'}
                </button>
                <button
                  className="sb-btn sb-btn-secondary"
                  onClick={() => setConfirmDlg(null)}
                  disabled={btnBusy}
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {history.length > 0 && (
        <div className="sb-table-section" style={{ marginTop: 32 }}>
          <div className="sb-table-head">
            <div className="title-group">
              <div className="title">Loan History</div>
              <div className="count">{history.length} RECORDS</div>
            </div>
          </div>
          <table className="sb-data-table">
            <thead>
              <tr>
                <th>Loan ID</th>
                <th>Amount</th>
                <th>Status</th>
                <th>EMI</th>
                <th>Remaining</th>
                <th>Due Date</th>
              </tr>
            </thead>
            <tbody>
              {history.map((h) => {
                const tone = h.status === 'APPROVED' ? 'success' : h.status === 'REJECTED' ? 'error' : 'warn';
                return (
                  <tr key={h.id}>
                    <td className="mono">#{h.id}</td>
                    <td className="mono">{fmtINR(h.amount)}</td>
                    <td>
                      <span className={`sb-tag-pill ${tone}`}>{h.status}</span>
                    </td>
                    <td className="mono">{fmtINR(h.monthlyEmi)}</td>
                    <td className="mono" style={{ color: 'var(--positive)' }}>
                      {fmtINR(h.due_amount)}
                    </td>
                    <td>{h.nextDueDate ? new Date(h.nextDueDate).toLocaleDateString() : '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
