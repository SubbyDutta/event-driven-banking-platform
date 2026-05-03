import React, { useEffect, useMemo, useRef, useState } from "react";
import API from "../../api";
import { motion, AnimatePresence } from "framer-motion";

const FINDOC_ADMIN_URL = (process.env.REACT_APP_FINDOC_ADMIN_URL || "http://localhost:5173").replace(/\/+$/, "");

const STATUS_CHIPS = [
  { value: "",                        label: "All" },
  { value: "PENDING_ADMIN_DECISION",  label: "Pending admin" },
  { value: "PENDING_USER_ACCEPTANCE", label: "Awaiting user" },
  { value: "MANUAL_REVIEW",           label: "Manual review" },
  { value: "DOCS_UNDER_REVIEW",       label: "Docs under review" },
  { value: "DOCS_VERIFIED",           label: "Docs verified" },
  { value: "RISK_EVALUATED",          label: "Risk evaluated" },
  { value: "APPROVED",                label: "Approved" },
  { value: "REJECTED",                label: "Rejected" },
  { value: "DOCS_REJECTED",           label: "Docs rejected" },
  { value: "DRAFT",                   label: "Draft" },
  { value: "FAILED",                  label: "Failed" },
];

const PAGE_SIZE = 10;
const AUTO_REFRESH_MS = 30000;

function fmtINR(v) {
  if (v == null) return "—";
  return "₹" + Number(v).toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

const STATUS_TONE = {
  APPROVED: "success",
  REJECTED: "error",
  DOCS_REJECTED: "error",
  MANUAL_REVIEW: "warn",
  PENDING_ADMIN_DECISION: "warn",
  PENDING_USER_ACCEPTANCE: "warn",
  DOCS_UNDER_REVIEW: "info",
  DOCS_VERIFIED: "info",
  RISK_EVALUATED: "info",
  FAILED: "muted",
  DRAFT: "muted",
};

const RECO_TONE = {
  APPROVE: "success",
  VERIFIED: "success",
  REJECT: "error",
  REJECTED: "error",
  MANUAL_REVIEW: "warn",
};

function inspectHref(loan) {
  if (!loan?.findocLoanApplicationId || !FINDOC_ADMIN_URL) return null;
  return `${FINDOC_ADMIN_URL}/app/${loan.findocLoanApplicationId}`;
}

function gradeFromBand(band) {
  if (!band) return null;
  const upper = String(band).toUpperCase();
  if (["A", "B", "C", "D"].includes(upper)) return upper;
  return null;
}

export default function LoanReviewView() {
  const [filter, setFilter] = useState("");
  const [onlyManual, setOnlyManual] = useState(false);
  const [query, setQuery] = useState("");
  const [loans, setLoans] = useState([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [quickOverride, setQuickOverride] = useState(null);
  const loadRef = useRef(null);

  const load = async (pageOverride) => {
    const p = pageOverride ?? page;
    setLoading(true);
    try {
      const path = onlyManual ? "/admin/loans/manual-review" : "/admin/loans";
      const params = { page: p, size: PAGE_SIZE };
      if (filter && !onlyManual) params.lifecycleStatus = filter;
      const res = await API.get(path, { params });
      setLoans(res.data.content || []);
      setTotal(res.data.totalElements ?? res.data.total ?? (res.data.content || []).length);
      setTotalPages(res.data.totalPages ?? Math.max(1, Math.ceil((res.data.totalElements ?? 0) / PAGE_SIZE)));
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };
  loadRef.current = load;

  useEffect(() => { setPage(0); /* eslint-disable-next-line */ }, [filter, onlyManual]);
  useEffect(() => { load(page); /* eslint-disable-next-line */ }, [page, filter, onlyManual]);

  useEffect(() => {
    const id = setInterval(() => {
      if (!quickOverride) loadRef.current?.(page);
    }, AUTO_REFRESH_MS);
    return () => clearInterval(id);
    // eslint-disable-next-line
  }, [page, filter, onlyManual, quickOverride]);

  const filtered = useMemo(() => {
    if (!query) return loans;
    const q = query.toLowerCase();
    return (loans || []).filter((l) => JSON.stringify(l).toLowerCase().includes(q));
  }, [query, loans]);

  return (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Underwriting</span>
            <span className="stamp">{total} APPLICATIONS</span>
          </div>
          <h1 className="sb-h-display">Loan <em>origination</em> review.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Read-only ledger across applications. Document edits and recommendation overrides happen
            in <em>findoc-verify</em> — click <strong>Inspect</strong> to open the underwriting workspace.
          </p>
        </div>
        <div className="right-meta">
          <div className="lab">Auto-refresh</div>
          <div className="val">{Math.round(AUTO_REFRESH_MS / 1000)}s</div>
        </div>
      </div>

      <div className="sb-admin-filter-stack">
        <div className="head">
          <div className="h">⌕ Application Filter</div>
          <div className="meta">{total} total · viewing {filtered.length}</div>
        </div>

        <div className="sb-admin-toggle-row" style={{ marginBottom: 14 }}>
          <button
            type="button"
            className={`sb-admin-toggle-pill${onlyManual ? " on" : ""}`}
            onClick={() => setOnlyManual((o) => !o)}
            aria-pressed={onlyManual}
            aria-label="Manual-review queue only"
          />
          <span>Manual-review queue only</span>
          <span style={{ marginLeft: "auto", fontSize: 11, color: "var(--muted)" }}>
            {onlyManual ? "Hides auto-decisions" : "Showing all states"}
          </span>
        </div>

        {!onlyManual && (
          <div className="sb-admin-chip-row" style={{ marginBottom: 14 }}>
            {STATUS_CHIPS.map((c) => (
              <button
                key={c.value || "all"}
                type="button"
                className={`sb-admin-chip${filter === c.value ? " active" : ""}`}
                onClick={() => setFilter(c.value)}
              >
                {c.label}
              </button>
            ))}
          </div>
        )}

        <div className="sb-admin-search-row">
          <div className="sb-admin-search-input">
            <input
              type="text"
              placeholder="Search by application ID, user, or any field…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          <button
            type="button"
            className="sb-btn sb-btn-secondary"
            onClick={() => load(page)}
            disabled={loading}
          >
            {loading ? <><span className="sb-spinner" /> Loading…</> : "↻ Refresh"}
          </button>
        </div>
      </div>

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <span className="title">{onlyManual ? "Manual-Review Queue" : "Loan Applications"}</span>
            <span className="count">{total} total · sorted by submission desc</span>
          </div>
        </div>

        <div style={{ overflowX: "auto" }}>
          <table className="sb-data-table">
            <thead>
              <tr>
                <th>Loan App ID</th>
                <th>User</th>
                <th>Amount · Purpose</th>
                <th>Doc verification</th>
                <th>ML result</th>
                <th>Status</th>
                <th>Risk</th>
                <th>Rate</th>
                <th>Submitted</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 && !loading && (
                <tr>
                  <td colSpan={10}>
                    <div className="sb-admin-table-empty">
                      <div className="h">No applications match</div>
                      <div>Try a different filter or clear the search.</div>
                    </div>
                  </td>
                </tr>
              )}
              {filtered.map((l) => (
                <tr key={l.loanAppId}>
                  <td>
                    <div className="mono" style={{ fontSize: 12 }}>{l.loanAppId?.slice(0, 8)}…</div>
                    <div style={{ fontSize: 10, color: "var(--muted)", marginTop: 2 }}>
                      LOAN APP
                    </div>
                  </td>
                  <td>
                    <span className="sb-mono" style={{ fontSize: 11 }}>{l.username}</span>
                  </td>
                  <td>
                    <div style={{ fontFamily: "'Fraunces', serif", fontWeight: 500, fontSize: 14 }}>
                      {fmtINR(l.amount)}
                    </div>
                    <div style={{ fontSize: 10, color: "var(--muted)", letterSpacing: "0.06em", marginTop: 2 }}>
                      {l.purpose ? String(l.purpose).toUpperCase() : "—"}
                    </div>
                  </td>
                  <td><DocVerificationCell loan={l} /></td>
                  <td><MlResultCell loan={l} /></td>
                  <td>
                    <span className={`sb-tag-pill ${STATUS_TONE[l.lifecycleStatus] || "muted"}`}>
                      <span className="dot" />
                      {l.lifecycleStatus}
                    </span>
                  </td>
                  <td>
                    {gradeFromBand(l.riskBand) ? (
                      <span className={`sb-admin-risk-grade ${gradeFromBand(l.riskBand)}`}>
                        {gradeFromBand(l.riskBand)}
                      </span>
                    ) : (
                      <span style={{ color: "var(--muted)" }}>—</span>
                    )}
                  </td>
                  <td>
                    <span className="sb-mono" style={{ fontSize: 13 }}>
                      {l.interestRate ? `${l.interestRate}%` : "—"}
                    </span>
                  </td>
                  <td>
                    <span className="sb-mono" style={{ fontSize: 11, color: "var(--muted)" }}>
                      {l.submittedAt ? new Date(l.submittedAt).toLocaleString("en-IN", { hour12: false }) : "—"}
                    </span>
                  </td>
                  <td>
                    <RowActions
                      loan={l}
                      onApprove={() => setQuickOverride({
                        loan: l, decision: "APPROVE",
                        rate: l.interestRate ? String(l.interestRate) : "",
                      })}
                      onReject={() => setQuickOverride({ loan: l, decision: "REJECT", rate: "" })}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="sb-table-foot">
          <div>
            Page <span className="sb-mono">{page + 1}</span> of{" "}
            <span className="sb-mono">{Math.max(totalPages || 1, 1)}</span> · {total} total
          </div>
          <div className="sb-pager">
            <button disabled={page === 0 || loading} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              ← Prev
            </button>
            <span className="sb-mono">
              PAGE {page + 1} / {Math.max(totalPages || 1, 1)}
            </span>
            <button disabled={page + 1 >= totalPages || loading} onClick={() => setPage((p) => p + 1)}>
              Next →
            </button>
          </div>
        </div>
      </div>

      <div className="sb-admin-footnote">
        <em>Risk grading ·</em> A = lowest risk (10.5–11.5%) · B = standard (12.0–13.0%) ·
        C = elevated (13.5–15.0%) · D = high (manual review). Grades are computed from bureau pull,
        DTI ratio, and document consistency. Override is logged immutably.
      </div>

      <AnimatePresence>
        {quickOverride && (
          <OverrideModal
            loanAppId={quickOverride.loan.loanAppId}
            current={quickOverride.loan.lifecycleStatus}
            defaults={{ decision: quickOverride.decision, interestRate: quickOverride.rate }}
            onClose={() => setQuickOverride(null)}
            onDone={() => { setQuickOverride(null); load(page); }}
          />
        )}
      </AnimatePresence>
    </>
  );
}

function DocVerificationCell({ loan }) {
  const reeval = loan?.docReevalResult;
  if (reeval) {
    const tone = RECO_TONE[String(reeval).toUpperCase()] || "muted";
    return (
      <div>
        <span className={`sb-tag-pill ${tone}`}>
          <span className="dot" />
          {reeval}
        </span>
        {loan.docReevalRunNumber != null && (
          <div style={{ fontSize: 10, color: "var(--muted)", marginTop: 4 }}>
            re-eval · run #{loan.docReevalRunNumber}
          </div>
        )}
      </div>
    );
  }
  const findoc = loan?.findocRecommendation;
  if (!findoc) return <span style={{ color: "var(--muted)" }}>—</span>;
  const tone = RECO_TONE[String(findoc).toUpperCase()] || "muted";
  return (
    <span className={`sb-tag-pill ${tone}`}>
      <span className="dot" />
      {findoc}
    </span>
  );
}

function MlResultCell({ loan }) {
  const ml = loan?.mlRecommendation;
  if (!ml) return <span style={{ color: "var(--muted)" }}>—</span>;
  const tone = RECO_TONE[String(ml).toUpperCase()] || "muted";
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
      <span className={`sb-tag-pill ${tone}`}>
        <span className="dot" />
        {ml}
      </span>
      {loan.riskBand && (
        <span
          className="sb-mono"
          style={{
            fontSize: 10,
            color: "var(--muted)",
            padding: "2px 6px",
            border: "1px solid var(--rule)",
            borderRadius: 1,
          }}
        >
          {loan.riskBand}
        </span>
      )}
    </div>
  );
}

function RowActions({ loan, onApprove, onReject }) {
  const href = inspectHref(loan);

  const goToFindoc = (e) => {
    if (!href) return;
    e.preventDefault();
    window.location.href = href;
  };

  return (
    <div className="sb-admin-action-group" style={{ gap: 8 }}>
      {href && (
        <a
          href={href}
          onClick={goToFindoc}
          className="sb-admin-action-link"
          title="Open this application in findoc-verify"
        >
          Inspect →
        </a>
      )}
      <button
        type="button"
        className="sb-admin-btn-inline success"
        onClick={onApprove}
        title="Force-approve with custom interest rate"
      >
        Approve
      </button>
      <button
        type="button"
        className="sb-admin-btn-inline danger"
        onClick={onReject}
        title="Force-reject this application"
      >
        Reject
      </button>
    </div>
  );
}

function OverrideModal({ loanAppId, current, defaults, onClose, onDone }) {
  const initialDecision = (() => {
    const d = defaults?.decision;
    if (d === "APPROVE" || d === "APPROVED") return "APPROVE";
    if (d === "REJECT" || d === "REJECTED") return "REJECT";
    return "APPROVE";
  })();
  const [decision, setDecision] = useState(initialDecision);
  const [reason, setReason] = useState("");
  const [rate, setRate] = useState(defaults?.interestRate || "");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const rateNum = rate === "" ? null : Number(rate);
  const rateInvalid =
    decision === "APPROVE" && (rate === "" || !Number.isFinite(rateNum) || rateNum <= 0 || rateNum > 50);
  const formInvalid = !reason.trim() || rateInvalid;

  const submit = async () => {
    if (!reason.trim()) { setErr("Reason is required"); return; }
    if (rateInvalid) { setErr("Interest rate must be between 0 (exclusive) and 50"); return; }
    setBusy(true); setErr("");
    try {
      const body = { decision, reason };
      if (decision === "APPROVE") body.interestRate = rateNum;
      await API.post(`/admin/loans/${loanAppId}/override`, body);
      onDone();
    } catch (e) {
      setErr(e?.response?.data?.error || "Override failed");
    } finally { setBusy(false); }
  };

  return (
    <motion.div
      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
      className="sb-modal-backdrop"
      onClick={() => !busy && onClose()}
    >
      <motion.div
        initial={{ opacity: 0, scale: 0.96, y: 12 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.96, y: 12 }}
        transition={{ type: "spring", damping: 24, stiffness: 260 }}
        className="sb-modal"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="sb-modal-head">
          <span className="corner-mark">REF · {loanAppId?.slice(0, 8)}</span>
          <div
            className="sb-seal"
            style={
              decision === "REJECT"
                ? { borderColor: "var(--accent-3)", color: "var(--accent-3)" }
                : undefined
            }
          >
            {decision === "APPROVE" ? "✓" : "✕"}
          </div>
          <h3>
            {decision === "APPROVE" ? <>Approve <em>loan</em></> : <>Reject <em>loan</em></>}
          </h3>
          <p>
            Current lifecycle:{" "}
            <span className={`sb-tag-pill ${STATUS_TONE[current] || "muted"}`}>
              <span className="dot" />
              {current}
            </span>
          </p>
        </div>

        <div className="sb-modal-body">
          <div className="sb-field" style={{ marginBottom: 14 }}>
            <label className="sb-admin-field-label">Decision</label>
            <select
              className="sb-field-input"
              value={decision}
              onChange={(e) => setDecision(e.target.value)}
            >
              <option value="APPROVE">APPROVE</option>
              <option value="REJECT">REJECT</option>
            </select>
          </div>

          {decision === "APPROVE" && (
            <div className="sb-field" style={{ marginBottom: 14 }}>
              <label className="sb-admin-field-label">
                Interest rate (% p.a.) <span className="req">*</span>
              </label>
              <input
                type="number"
                step="0.01"
                min="0"
                max="50"
                value={rate}
                onChange={(e) => setRate(e.target.value)}
                placeholder="e.g. 12.5"
                className="sb-field-input mono"
                style={rateInvalid ? { borderColor: "var(--accent-3)" } : undefined}
              />
              <div
                className="sb-field-help"
                style={rateInvalid ? { color: "var(--accent-3)" } : undefined}
              >
                Required for APPROVE. Must be greater than 0 and at most 50.
              </div>
            </div>
          )}

          <div className="sb-field" style={{ marginBottom: 16 }}>
            <label className="sb-admin-field-label">
              Reason <span className="req">*</span>
            </label>
            <textarea
              rows={3}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why is this decision being applied?"
              className="sb-field-input"
            />
          </div>

          {err && <div className="sb-alert sb-alert-error">{err}</div>}

          <div className="sb-btn-row" style={{ paddingTop: 8 }}>
            <button
              type="button"
              className="sb-btn sb-btn-secondary"
              onClick={onClose}
              disabled={busy}
            >
              Cancel
            </button>
            <span style={{ marginLeft: "auto" }} />
            <button
              type="button"
              className="sb-btn sb-btn-primary"
              onClick={submit}
              disabled={busy || formInvalid}
              style={
                decision === "REJECT" && !busy && !formInvalid
                  ? { background: "var(--accent-3)" }
                  : undefined
              }
            >
              {busy ? (
                <><span className="sb-spinner" /> Applying…</>
              ) : decision === "APPROVE" ? (
                "Approve & disburse"
              ) : (
                "Reject"
              )}
            </button>
          </div>
        </div>
      </motion.div>
    </motion.div>
  );
}
