import React, { useEffect, useMemo, useState } from "react";
import { ExternalLink, RefreshCw, Search } from "lucide-react";
import API from "../../api";

const STATUS_TONE = {
  NONE: "muted",
  KYC_SUBMITTED: "info",
  KYC_DOCS_UNDER_REVIEW: "info",
  KYC_APPROVED: "success",
  KYC_REJECTED: "error",
  KYC_MANUAL_REVIEW: "warn",
};

const FINDOC_WEBUI = process.env.REACT_APP_FINDOC_WEBUI_URL || "http://localhost:5173";

function fmtDate(v) {
  if (!v) return "—";
  try {
    const d = new Date(v);
    if (isNaN(d.getTime())) return String(v);
    return (
      d.toLocaleDateString("en-IN", { year: "numeric", month: "short", day: "numeric" }) +
      " · " +
      d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false })
    );
  } catch {
    return String(v);
  }
}

export default function KycUsersView() {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [error, setError] = useState("");
  const [findocKey, setFindocKey] = useState("");

  useEffect(() => {
    API.get("/admin/findoc-key")
      .then((r) => setFindocKey(r.data?.key || ""))
      .catch(() => setFindocKey(""));
  }, []);

  function buildInspectUrl(applicationId) {
    const base = `${FINDOC_WEBUI}/app/${applicationId}`;
    return findocKey ? `${base}#key=${encodeURIComponent(findocKey)}` : base;
  }

  async function load() {
    setLoading(true);
    setError("");
    try {
      const params = {};
      if (q) params.q = q;
      if (statusFilter) params.kycStatus = statusFilter;
      const res = await API.get("/admin/kyc/users", { params });
      setItems(res.data?.items || []);
    } catch (e) {
      setError(e?.response?.data?.error || e?.message || "Failed to load");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const counts = useMemo(() => {
    const totals = { approved: 0, pending: 0, rejected: 0, none: 0 };
    items.forEach((u) => {
      const k = String(u.kycStatus || "NONE");
      if (k === "KYC_APPROVED") totals.approved += 1;
      else if (k === "KYC_REJECTED") totals.rejected += 1;
      else if (k === "NONE") totals.none += 1;
      else totals.pending += 1;
    });
    return totals;
  }, [items]);

  return (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Identity Pipeline</span>
            <span className="stamp">FINDOC-VERIFY</span>
          </div>
          <h1 className="sb-h-display">Verify <em>before you trust</em>.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Cross-checked with findoc-verify via user ID mapping. For mismatches or rejected KYCs,
            click <em>Review in findoc</em> to inspect the pipeline timeline (OCR, classification,
            compliance, cross-doc, fraud) and override the decision.
          </p>
        </div>
        <div className="right-meta">
          <div className="lab">Today</div>
          <div className="val large">{items.length}</div>
        </div>
      </div>

      <div className="sb-admin-kpi-row">
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">A</div>
          <div className="label">Approved</div>
          <div className="value mono ok">{counts.approved}</div>
          <div className="footnote">CLEARED FOR USE</div>
        </div>
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">B</div>
          <div className="label">Pending Decision</div>
          <div className={`value mono${counts.pending > 0 ? " warn" : ""}`}>{counts.pending}</div>
          <div className="footnote">QUEUE · MANUAL REVIEW</div>
        </div>
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">C</div>
          <div className="label">Rejected</div>
          <div className={`value mono${counts.rejected > 0 ? " crit" : ""}`}>{counts.rejected}</div>
          <div className="footnote">DOC MISMATCH</div>
        </div>
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">D</div>
          <div className="label">None Submitted</div>
          <div className="value mono muted">{counts.none}</div>
          <div className="footnote">NEW ACCOUNT</div>
        </div>
      </div>

      {error && (
        <div className="sb-alert sb-alert-error" style={{ marginBottom: 16 }}>
          <span>⚠</span><span>{error}</span>
        </div>
      )}

      <div className="sb-admin-filter-stack">
        <div className="head">
          <div className="h">⌕ Filter Queue</div>
          <div className="meta">{items.length} records</div>
        </div>
        <div className="sb-admin-search-row">
          <div className="sb-admin-icon-input">
            <Search size={14} className="icn" />
            <input
              type="text"
              placeholder="Search by username, email, or user ID…"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && load()}
            />
          </div>
          <select
            className="sb-admin-select"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
          >
            <option value="">All statuses</option>
            <option value="NONE">NONE</option>
            <option value="KYC_SUBMITTED">KYC_SUBMITTED</option>
            <option value="KYC_DOCS_UNDER_REVIEW">KYC_DOCS_UNDER_REVIEW</option>
            <option value="KYC_APPROVED">KYC_APPROVED</option>
            <option value="KYC_REJECTED">KYC_REJECTED</option>
            <option value="KYC_MANUAL_REVIEW">KYC_MANUAL_REVIEW</option>
          </select>
          <button type="button" className="sb-btn sb-btn-primary" onClick={load}>
            Apply →
          </button>
          <button
            type="button"
            className="sb-btn sb-btn-secondary"
            onClick={load}
            disabled={loading}
          >
            <RefreshCw size={14} /> Refresh
          </button>
        </div>
      </div>

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <span className="title">Verification Queue</span>
            <span className="count">Cross-checked · findoc-verify pipeline</span>
          </div>
        </div>

        <div style={{ overflowX: "auto" }}>
          <table className="sb-data-table">
            <thead>
              <tr>
                <th>User</th>
                <th>Email</th>
                <th>KYC Status</th>
                <th>Submitted</th>
                <th>Decided</th>
                <th className="right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={6}>
                    <div className="sb-admin-table-empty">
                      <span className="sb-spinner" /> Loading…
                    </div>
                  </td>
                </tr>
              )}
              {!loading && items.length === 0 && (
                <tr>
                  <td colSpan={6}>
                    <div className="sb-admin-table-empty">
                      <div className="h">No users match</div>
                      <div>Try a different filter or clear the search.</div>
                    </div>
                  </td>
                </tr>
              )}
              {!loading && items.map((u) => {
                const tone = STATUS_TONE[u.kycStatus] || "muted";
                const isRejected = u.kycStatus === "KYC_REJECTED";
                return (
                  <tr key={u.id}>
                    <td>
                      <div className="sb-mono" style={{ fontSize: 12 }}>{u.username}</div>
                      <div style={{ fontSize: 10, color: "var(--muted)", marginTop: 2 }}>
                        UID · {u.id}
                      </div>
                    </td>
                    <td>
                      <span className="sb-mono" style={{ fontSize: 11 }}>{u.email || "—"}</span>
                    </td>
                    <td>
                      <span className={`sb-tag-pill ${tone}`}>
                        <span className="dot" />
                        {u.kycStatus || "NONE"}
                      </span>
                    </td>
                    <td>
                      <span className="sb-mono" style={{ fontSize: 11, color: "var(--muted)" }}>
                        {fmtDate(u.kycSubmittedAt)}
                      </span>
                    </td>
                    <td>
                      <span className="sb-mono" style={{ fontSize: 11, color: "var(--muted)" }}>
                        {fmtDate(u.kycDecidedAt)}
                      </span>
                    </td>
                    <td className="right">
                      {u.findocKycApplicationId ? (
                        <a
                          href={buildInspectUrl(u.findocKycApplicationId)}
                          target="_blank"
                          rel="noopener noreferrer"
                          className={`sb-admin-review-link${isRejected ? " danger" : ""}`}
                          title="Review & override in findoc-verify"
                        >
                          {isRejected ? "Override" : "Review in findoc"}
                          <ExternalLink size={12} />
                        </a>
                      ) : (
                        <span style={{ color: "var(--muted)", fontSize: 11 }}>—</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        <div className="sb-table-foot">
          <div>
            Pipeline stages:{" "}
            <span className="sb-mono" style={{ color: "var(--ink)" }}>
              OCR · CLASSIFY · COMPLIANCE · CROSS-DOC · FRAUD
            </span>
          </div>
        </div>
      </div>

      <div className="sb-admin-footnote">
        <em>How to use ·</em> For mismatches or rejected KYCs, click{" "}
        <strong>Review in findoc</strong> to inspect the pipeline timeline and override the
        decision. Java keeps its own copy of KYC state on the User row, updated from the
        findoc-verify result event.
      </div>
    </>
  );
}
